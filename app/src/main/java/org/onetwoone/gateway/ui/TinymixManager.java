package org.onetwoone.gateway.ui;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages tinymix binary extraction and mixer control detection.
 *
 * Handles:
 * - Extracting tinymix binary from assets
 * - Running tinymix commands via root
 * - Parsing mixer controls (DEC volume, MUX, speaker)
 */
public class TinymixManager {
    private static final String TAG = "TinymixManager";

    /**
     * Represents a mixer control detected from tinymix output.
     */
    public static class MixerControl {
        public String name;         // e.g. "DEC1 Volume", "DEC1 MUX", "EAR_S"
        public int controlId;       // tinymix control ID
        public String currentValue; // Current value (volume or mux value)
        public ControlType type;
        public int originalValue;   // Original volume value for restore

        public MixerControl(String name, int controlId, String currentValue, ControlType type) {
            this.name = name;
            this.controlId = controlId;
            this.currentValue = currentValue;
            this.type = type;
            this.originalValue = -1;
        }

        @Override
        public String toString() {
            return name + " (" + currentValue + ")";
        }
    }

    public enum ControlType {
        VOLUME,     // DEC Volume controls (INT type)
        MUX,        // DEC MUX controls (ENUM type)
        SPEAKER     // Speaker/Earpiece controls (EAR_S, SPK)
    }

    private final Context context;
    private File tinymixFile;

    public TinymixManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Extract tinymix binary from assets if not already extracted.
     *
     * @return true if tinymix is available, false otherwise
     */
    public boolean ensureTinymixExtracted() {
        if (tinymixFile != null && tinymixFile.exists()) {
            return true;
        }

        // Always use tinymix-arm64 (64-bit) since the APK is built for arm64-v8a only
        String assetName = "tinymix-arm64";
        tinymixFile = new File(context.getFilesDir(), "tinymix");

        if (tinymixFile.exists()) {
            return true;
        }

        try {
            InputStream is = context.getAssets().open(assetName);
            FileOutputStream os = new FileOutputStream(tinymixFile);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) > 0) {
                os.write(buffer, 0, len);
            }
            os.close();
            is.close();

            // Make executable
            tinymixFile.setExecutable(true, false);

            Log.i(TAG, "Extracted " + assetName + " to " + tinymixFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract " + assetName + ": " + e.getMessage());
            tinymixFile = null;
            return false;
        }
    }

    /**
     * Run tinymix for a specific sound card and return raw output.
     *
     * @param soundCard The sound card number
     * @return Raw tinymix output or empty string on failure
     */
    public String runTinymix(int soundCard) {
        if (!ensureTinymixExtracted()) {
            return "";
        }

        try {
            Process p;
            // Android 11+ requires root for mixer control access due to SELinux
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // Android 11+ (API 30+): use su
                p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c", tinymixFile.getAbsolutePath() + " -D " + soundCard
                });
            } else {
                // Android 8.1-10: direct access works
                p = Runtime.getRuntime().exec(new String[]{
                    tinymixFile.getAbsolutePath(), "-D", String.valueOf(soundCard)
                });
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            p.waitFor();
            return output.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to run tinymix: " + e.getMessage());
            return "";
        }
    }

    /**
     * Detect all mixer controls for the given sound card.
     *
     * Detects:
     * - DEC Volume controls (microphone volume)
     * - DEC MUX controls (microphone routing)
     * - Speaker/Earpiece controls (EAR_S, SPK)
     *
     * @param soundCard The sound card number
     * @return List of detected mixer controls
     */
    public List<MixerControl> detectControls(int soundCard) {
        List<MixerControl> controls = new ArrayList<>();
        String output = runTinymix(soundCard);

        if (output.isEmpty()) {
            Log.w(TAG, "No tinymix output - control detection failed");
            return controls;
        }

        // Parse tinymix output to find ALL mute controls:
        // Format examples:
        // 33   INT  1  DEC1 Volume  84
        // 1686 ENUM 1  DEC1 MUX     ZERO
        // 105  ENUM 1  EAR_S        ZERO
        // 106  ENUM 1  SPK          ZERO
        Pattern volumePattern = Pattern.compile("^(\\d+)\\s+INT\\s+\\d+\\s+(DEC\\d+) Volume\\s+(\\d+)");
        Pattern muxPattern = Pattern.compile("^(\\d+)\\s+ENUM\\s+\\d+\\s+(DEC\\d+) MUX\\s+(\\w+)");
        Pattern speakerPattern = Pattern.compile("^(\\d+)\\s+ENUM\\s+\\d+\\s+(EAR_S|SPK)\\s+(\\w+)");

        String[] lines = output.split("\n");
        for (String line : lines) {
            // Find DEC Volume controls
            Matcher volMatcher = volumePattern.matcher(line);
            if (volMatcher.find()) {
                String decNum = volMatcher.group(2);  // DEC1, DEC2, etc.
                int controlId = Integer.parseInt(volMatcher.group(1));
                String value = volMatcher.group(3);
                MixerControl control = new MixerControl(decNum + " Volume", controlId, value, ControlType.VOLUME);
                control.originalValue = Integer.parseInt(value);
                controls.add(control);
                Log.i(TAG, "Found DEC Volume: " + control + " (ID=" + controlId + ")");
            }

            // Find DEC MUX controls
            Matcher muxMatcher = muxPattern.matcher(line);
            if (muxMatcher.find()) {
                String decNum = muxMatcher.group(2);  // DEC1, DEC2, etc.
                int controlId = Integer.parseInt(muxMatcher.group(1));
                String muxValue = muxMatcher.group(3);  // ADC1, ADC2, ZERO, etc.
                MixerControl control = new MixerControl(decNum + " MUX", controlId, muxValue, ControlType.MUX);
                controls.add(control);
                Log.i(TAG, "Found DEC MUX: " + control + " (ID=" + controlId + ")");
            }

            // Find Speaker/Earpiece controls (EAR_S, SPK)
            Matcher spkMatcher = speakerPattern.matcher(line);
            if (spkMatcher.find()) {
                String spkName = spkMatcher.group(2);  // EAR_S or SPK
                int controlId = Integer.parseInt(spkMatcher.group(1));
                String spkValue = spkMatcher.group(3);  // ZERO, Switch, etc.
                MixerControl control = new MixerControl(spkName, controlId, spkValue, ControlType.SPEAKER);
                controls.add(control);
                Log.i(TAG, "Found Speaker control: " + control + " (ID=" + controlId + ")");
            }
        }

        Log.i(TAG, "Detected " + controls.size() + " mixer controls for card " + soundCard);
        return controls;
    }
}
