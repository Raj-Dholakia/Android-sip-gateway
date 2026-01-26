package org.onetwoone.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.pjsip.pjsua2.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom AudioMediaPort for bridging GSM call audio to SIP.
 * Uses native tinyalsa for direct ALSA access - no tinycap/tinyplay processes.
 *
 * All device parameters are configurable via SharedPreferences.
 */
public class GsmAudioPort extends AudioMediaPort {
    private static final String TAG = "GsmAudioPort";
    private static final String PREFS_NAME = "gsm_audio_config";

    // Audio parameters (GSM compatible)
    private static final int SAMPLE_RATE = 8000;
    private static final int CHANNELS = 1;
    private static final int BITS = 16;
    private static final int FRAME_TIME_MS = 20;
    private static final int FRAME_SIZE = SAMPLE_RATE * (BITS / 8) * CHANNELS * FRAME_TIME_MS / 1000;  // 320 bytes
    private static final int PERIOD_SIZE = 160;  // samples per period (20ms @ 8kHz)
    private static final int PERIOD_COUNT = 4;

    // Configurable device parameters (loaded from SharedPreferences)
    private int card = 0;
    private int captureDevice = 0;      // PCM device for VOC_REC capture
    private int playbackDevice = 0;     // PCM device for Incall_Music playback
    private String multimediaRoute = "MultiMedia1";  // Mixer route name

    // Microphone mute controls (device-specific) - can mute multiple DECs
    private List<String> micMuteControls = new ArrayList<>();  // e.g., ["DEC1 MUX", "DEC2 MUX"]
    private Map<String, Integer> micOriginalValues = new HashMap<>();  // Store original INT values for restore
    private Map<String, String> micOriginalEnumValues = new HashMap<>();  // Store original ENUM values for MUX restore

    private Context context;
    private AtomicBoolean isCapturing = new AtomicBoolean(false);
    private AtomicBoolean isPortCreated = new AtomicBoolean(false);

    // Native read/write buffers (reused to avoid allocation)
    private byte[] captureBuffer;
    private byte[] playbackBuffer;

    // Statistics
    private long framesRequested = 0;
    private long framesReceived = 0;
    private long captureErrors = 0;
    private long playbackErrors = 0;

    public GsmAudioPort(Context context) {
        super();
        this.context = context;
        this.captureBuffer = new byte[FRAME_SIZE];
        this.playbackBuffer = new byte[FRAME_SIZE];
        loadConfig();
    }

    /**
     * Load device configuration from SharedPreferences
     */
    private void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        card = prefs.getInt("card", 0);
        captureDevice = prefs.getInt("capture_device", 0);
        playbackDevice = prefs.getInt("playback_device", 0);
        multimediaRoute = prefs.getString("multimedia_route", "MultiMedia1");

        // Load DEC mute controls (comma-separated list)
        String decList = prefs.getString("mic_mute_decs", "");
        micMuteControls.clear();
        if (!decList.isEmpty()) {
            for (String dec : decList.split(",")) {
                micMuteControls.add(dec.trim());
            }
        }

        Log.i(TAG, "Config loaded: card=" + card + ", capture=" + captureDevice +
              ", playback=" + playbackDevice + ", route=" + multimediaRoute +
              ", micMuteDECs=" + micMuteControls);
    }

    /**
     * Save device configuration to SharedPreferences
     */
    public void saveConfig(int card, int captureDevice, int playbackDevice, String multimediaRoute) {
        this.card = card;
        this.captureDevice = captureDevice;
        this.playbackDevice = playbackDevice;
        this.multimediaRoute = multimediaRoute;

        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt("card", card);
        editor.putInt("capture_device", captureDevice);
        editor.putInt("playback_device", playbackDevice);
        editor.putString("multimedia_route", multimediaRoute);
        editor.apply();

        Log.i(TAG, "Config saved: card=" + card + ", capture=" + captureDevice +
              ", playback=" + playbackDevice + ", route=" + multimediaRoute);
    }

    /**
     * Initialize native audio
     */
    public boolean initialize() {
        Log.d(TAG, "Initializing GsmAudioPort (native mode)...");

        // Setup ALSA permissions (requires root)
        if (!RootHelper.setupAlsaPermissions()) {
            Log.e(TAG, "Failed to setup ALSA permissions - native audio won't work");
            return false;
        }

        // Log available mixer controls for debugging on new devices
        GsmAudioNative.logMixerControls(card);

        return true;
    }

    /**
     * Create PJSIP audio port
     */
    public void createPort() {
        if (isPortCreated.get()) {
            Log.d(TAG, "Port already created");
            return;
        }

        try {
            MediaFormatAudio fmt = new MediaFormatAudio();
            fmt.setType(pjmedia_type.PJMEDIA_TYPE_AUDIO);
            fmt.setId(pjmedia_format_id.PJMEDIA_FORMAT_L16);
            fmt.setClockRate(SAMPLE_RATE);
            fmt.setChannelCount(CHANNELS);
            fmt.setBitsPerSample(BITS);
            fmt.setFrameTimeUsec(FRAME_TIME_MS * 1000);

            super.createPort("gsm_bridge", fmt);
            isPortCreated.set(true);

            Log.d(TAG, "Audio port created: " + SAMPLE_RATE + "Hz, " + CHANNELS + "ch, " + BITS + "bit, frame=" + FRAME_SIZE);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create port: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: Need audio to SEND to SIP peer (GSM → SIP direction)
     */
    @Override
    public void onFrameRequested(MediaFrame frame) {
        framesRequested++;

        try {
            ByteVector buf = frame.getBuf();
            buf.clear();

            if (isCapturing.get() && GsmAudioNative.isOpen()) {
                // Read from native ALSA
                int bytesRead = GsmAudioNative.readFrame(captureBuffer);

                if (bytesRead == FRAME_SIZE) {
                    for (byte b : captureBuffer) {
                        buf.add((short) (b & 0xFF));
                    }
                    frame.setSize(FRAME_SIZE);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
                } else {
                    captureErrors++;
                    // Send silence on error
                    for (int i = 0; i < FRAME_SIZE; i++) buf.add((short) 0);
                    frame.setSize(FRAME_SIZE);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
                }
            } else {
                // Not capturing - send silence
                for (int i = 0; i < FRAME_SIZE; i++) buf.add((short) 0);
                frame.setSize(FRAME_SIZE);
                frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
            }

            // Log every 500 frames (~10 seconds)
            if (framesRequested % 500 == 0) {
                Log.d(TAG, "onFrameRequested: " + framesRequested + " frames, errors=" + captureErrors);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameRequested: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: RECEIVED audio from SIP peer (SIP → GSM direction)
     */
    @Override
    public void onFrameReceived(MediaFrame frame) {
        framesReceived++;

        try {
            if (!isCapturing.get() || !GsmAudioNative.isOpen()) {
                return;
            }

            ByteVector buf = frame.getBuf();
            long size = frame.getSize();

            if (buf != null && size > 0 && size <= FRAME_SIZE) {
                // Convert ByteVector to byte[]
                for (int i = 0; i < size; i++) {
                    playbackBuffer[i] = (byte) (buf.get(i) & 0xFF);
                }

                // Write to native ALSA
                int bytesWritten = GsmAudioNative.writeFrame(playbackBuffer);
                if (bytesWritten < 0) {
                    playbackErrors++;
                }
            }

            // Log every 500 frames (~10 seconds)
            if (framesReceived % 500 == 0) {
                Log.d(TAG, "onFrameReceived: " + framesReceived + " frames, errors=" + playbackErrors);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameReceived: " + e.getMessage());
        }
    }

    /**
     * Start audio capture/playback (when GSM call becomes active)
     */
    public void startCapture() {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing");
            return;
        }

        Log.d(TAG, "Starting native audio...");

        // Setup mixer controls
        boolean mixerOk = setupMixer();
        if (!mixerOk) {
            Log.w(TAG, "Mixer setup failed, audio may not work");
        }

        // Open native audio devices
        boolean opened = GsmAudioNative.open(
            card, captureDevice, playbackDevice,
            SAMPLE_RATE, CHANNELS, BITS,
            PERIOD_SIZE, PERIOD_COUNT
        );

        if (!opened) {
            Log.e(TAG, "Failed to open native audio devices!");
            Log.e(TAG, "Check: 1) Root access 2) Device permissions 3) Correct device numbers");
            return;
        }

        isCapturing.set(true);
        Log.d(TAG, "Native audio started");
    }

    /**
     * Stop audio capture/playback
     */
    public void stopCapture() {
        Log.d(TAG, "Stopping native audio...");

        isCapturing.set(false);

        // Close native audio
        GsmAudioNative.close();

        // Teardown mixer
        teardownMixer();

        Log.d(TAG, "Native audio stopped. Stats: requested=" + framesRequested +
              ", received=" + framesReceived +
              ", captureErr=" + captureErrors + ", playbackErr=" + playbackErrors);

        // Reset statistics
        framesRequested = 0;
        framesReceived = 0;
        captureErrors = 0;
        playbackErrors = 0;
    }

    public void stop() {
        stopCapture();
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }

    // ========== Mixer Controls ==========

    private boolean setupMixer() {
        Log.d(TAG, "Setting up mixer for " + multimediaRoute + "...");

        boolean ok = true;

        // Enable VOC_REC capture (DL only - UL would capture Incall_Music and cause echo!)
        ok &= GsmAudioNative.setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 1);
        // VOC_REC_UL disabled - it captures uplink including Incall_Music, causing echo

        // Enable Incall_Music playback for BOTH SIM slots
        // SIM1 uses Incall_Music, SIM2 uses Incall_Music_2
        ok &= GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + multimediaRoute, 1);
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 1);

        // Mute ALL configured controls (microphone DECs + speaker EAR_S/SPK)
        // Different devices use different DECs, so we mute ALL of them
        // Types: Volume controls (INT), MUX controls (ENUM), Speaker controls (ENUM)
        if (!micMuteControls.isEmpty()) {
            micOriginalValues.clear();
            micOriginalEnumValues.clear();
            for (String decControl : micMuteControls) {
                // Check control type by name
                if (decControl.contains(" Volume")) {
                    // INT control - set to 0
                    int originalValue = readMixerControlValue(decControl);
                    micOriginalValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControl(card, decControl, 0);
                    Log.d(TAG, "Muted: " + decControl + " = 0 (original=" + originalValue + ")");
                } else if (decControl.contains(" MUX")) {
                    // ENUM MUX control - set to "ZERO"
                    String originalValue = readMixerControlEnum(decControl);
                    micOriginalEnumValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControlEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Muted: " + decControl + " = ZERO (original=" + originalValue + ")");
                } else if (decControl.equals("EAR_S") || decControl.equals("SPK")) {
                    // Speaker/Earpiece ENUM control - set to "ZERO"
                    String originalValue = readMixerControlEnum(decControl);
                    micOriginalEnumValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControlEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Speaker muted: " + decControl + " = ZERO (original=" + originalValue + ")");
                }
            }
        }

        if (ok) {
            Log.d(TAG, "Mixer setup OK");
        } else {
            Log.w(TAG, "Mixer setup incomplete - some controls may not exist on this device");
        }

        return ok;
    }

    private void teardownMixer() {
        Log.d(TAG, "Tearing down mixer...");

        GsmAudioNative.setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 0);
        // VOC_REC_UL not used (causes echo)
        GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + multimediaRoute, 0);
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + multimediaRoute, 0);

        // Restore ALL muted controls (Volume, MUX, and Speaker)
        for (String decControl : micMuteControls) {
            if (decControl.contains(" Volume")) {
                // Restore Volume control
                Integer originalValue = micOriginalValues.get(decControl);
                if (originalValue != null) {
                    GsmAudioNative.setMixerControl(card, decControl, originalValue);
                    Log.d(TAG, "Restored: " + decControl + " = " + originalValue);
                }
            } else if (decControl.contains(" MUX") || decControl.equals("EAR_S") || decControl.equals("SPK")) {
                // Restore MUX or Speaker ENUM control
                String originalValue = micOriginalEnumValues.get(decControl);
                if (originalValue != null && !originalValue.isEmpty()) {
                    GsmAudioNative.setMixerControlEnum(card, decControl, originalValue);
                    Log.d(TAG, "Restored: " + decControl + " = " + originalValue);
                }
            }
        }
        micOriginalValues.clear();
        micOriginalEnumValues.clear();
    }

    /**
     * Read current mixer control value via tinymix.
     * Returns -1 if failed to read.
     */
    private int readMixerControlValue(String controlName) {
        try {
            // Use tinymix to get current value
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "tinymix -D " + card + " get \"" + controlName + "\""
            });
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            if (line != null) {
                // Parse "84" or "84 (range 0->124)" format
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0) {
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read mixer control " + controlName + ": " + e.getMessage());
        }
        return 84;  // Default fallback value
    }

    /**
     * Read current mixer control ENUM value via tinymix.
     * Returns empty string if failed to read.
     */
    private String readMixerControlEnum(String controlName) {
        try {
            File tinymixFile = new File(context.getFilesDir(), "tinymix");
            // Use tinymix to get current ENUM value
            Process p = Runtime.getRuntime().exec(new String[]{
                tinymixFile.getAbsolutePath(), "-D", String.valueOf(card), "get", controlName
            });
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            if (line != null) {
                // Parse "ZERO" or "ADC1" etc.
                return line.trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read mixer control ENUM " + controlName + ": " + e.getMessage());
        }
        return "";  // Default fallback
    }

}
