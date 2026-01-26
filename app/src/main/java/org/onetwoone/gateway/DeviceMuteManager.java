package org.onetwoone.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages device-specific mute controls for speaker and microphone.
 *
 * Different Qualcomm devices have different mixer controls.
 * This class provides presets for known devices and allows custom configuration.
 *
 * To add support for a new device:
 * 1. Run: adb shell "su -c 'tinymix'" during an active call
 * 2. Find controls for speaker (EAR_S, SPK, RCV, etc.) and mic (DEC Volume/MUX)
 * 3. Add a new preset below
 */
public class DeviceMuteManager {
    private static final String TAG = "DeviceMute";
    private static final String PREFS_NAME = "device_mute_prefs";
    private static final String PREF_PRESET = "mute_preset";

    // Preset names
    public static final String PRESET_CUSTOM = "custom";
    public static final String PRESET_REDMI_NOTE_7 = "redmi_note_7";      // SDM660
    public static final String PRESET_GENERIC = "generic";                // Generic SDM4xx
    public static final String PRESET_REDMI_4X = "redmi_4x";              // MSM8940 / SD435

    // ============================================================
    // DEVICE PRESETS - Edit these for your device!
    // ============================================================

    private static final Map<String, DevicePreset> PRESETS = new HashMap<>();

    static {
        // Redmi Note 7 (SDM660) - tested on LineageOS 17.1
        PRESETS.put(PRESET_REDMI_NOTE_7, new DevicePreset(
            "Redmi Note 7 (SDM660)",
            new String[] {
                // Speaker/Earpiece mute (ENUM -> ZERO)
                "EAR_S",
                "SPK"
            },
            new String[] {
                // Microphone mute (INT -> 0)
                "DEC1 Volume",
                "DEC2 Volume",
                "DEC3 Volume",
                "DEC4 Volume",
                "DEC5 Volume"
            },
            new String[] {
                // Microphone routing mute (ENUM -> ZERO)
                "DEC1 MUX",
                "DEC2 MUX",
                "DEC3 MUX",
                "DEC4 MUX",
                "DEC5 MUX"
            }
        ));

        // Generic preset for SDM4xx devices (SD425, SD435, etc.)
        PRESETS.put(PRESET_GENERIC, new DevicePreset(
            "Generic (SDM4xx)",
            new String[] {
                // Speaker mute - check with tinymix on your device!
                "EAR_S",
                "SPK"
            },
            new String[] {
                // Microphone mute
                "DEC1 Volume",
                "DEC2 Volume",
                "DEC3 Volume",
                "DEC4 Volume"
            },
            new String[] {
                // Microphone routing
                "DEC1 MUX",
                "DEC2 MUX",
                "DEC3 MUX",
                "DEC4 MUX"
            }
        ));

        // Redmi 4X (MSM8940 / Snapdragon 435)
        PRESETS.put(PRESET_REDMI_4X, new DevicePreset(
            "Redmi 4X (SD435)",
            new String[] {
                "EAR_S",
                "SPK"
            },
            new String[] {
                "DEC1 Volume",
                "DEC2 Volume",
                "DEC3 Volume",
                "DEC4 Volume"
            },
            new String[] {
                "DEC1 MUX",
                "DEC2 MUX",
                "DEC3 MUX",
                "DEC4 MUX"
            }
        ));
    }

    // ============================================================
    // Instance fields
    // ============================================================

    private Context context;
    private int soundCard = 0;
    private String currentPreset = PRESET_CUSTOM;
    private boolean isMuted = false;

    // Original values for restore
    private Map<String, String> originalEnumValues = new HashMap<>();
    private Map<String, Integer> originalIntValues = new HashMap<>();

    // Singleton
    private static DeviceMuteManager instance;

    public static synchronized DeviceMuteManager getInstance(Context context) {
        if (instance == null) {
            instance = new DeviceMuteManager(context.getApplicationContext());
        }
        return instance;
    }

    private DeviceMuteManager(Context context) {
        this.context = context;
        loadPreset();
    }

    /**
     * Load saved preset from SharedPreferences
     */
    private void loadPreset() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        currentPreset = prefs.getString(PREF_PRESET, PRESET_REDMI_NOTE_7);  // Default

        // Read sound card from gsm_audio_config (same as GsmAudioPort uses)
        SharedPreferences audioPrefs = context.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
        soundCard = audioPrefs.getInt("card", 0);

        Log.d(TAG, "Loaded preset: " + currentPreset + ", card: " + soundCard);
    }

    /**
     * Save current preset to SharedPreferences
     */
    public void savePreset(String presetName) {
        currentPreset = presetName;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(PREF_PRESET, presetName);
        editor.apply();
        Log.d(TAG, "Saved preset: " + presetName);
    }

    /**
     * Set sound card number
     */
    public void setSoundCard(int card) {
        this.soundCard = card;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt("sound_card", card);
        editor.apply();
    }

    // Ordered list of preset keys to ensure consistent iteration
    private static final String[] PRESET_ORDER = {
        PRESET_REDMI_NOTE_7,
        PRESET_GENERIC,
        PRESET_REDMI_4X,
        PRESET_CUSTOM
    };

    /**
     * Get list of available preset names (in consistent order)
     */
    public static String[] getPresetNames() {
        return PRESET_ORDER.clone();
    }

    /**
     * Get human-readable preset descriptions (matching order of getPresetNames)
     */
    public static String[] getPresetDescriptions() {
        String[] descriptions = new String[PRESET_ORDER.length];
        for (int i = 0; i < PRESET_ORDER.length; i++) {
            if (PRESET_ORDER[i].equals(PRESET_CUSTOM)) {
                descriptions[i] = "Custom (select controls manually)";
            } else {
                DevicePreset preset = PRESETS.get(PRESET_ORDER[i]);
                descriptions[i] = (preset != null) ? preset.description : PRESET_ORDER[i];
            }
        }
        return descriptions;
    }

    /**
     * Check if current preset is custom
     */
    public boolean isCustomPreset() {
        return PRESET_CUSTOM.equals(currentPreset);
    }

    /**
     * Get current preset name
     */
    public String getCurrentPreset() {
        return currentPreset;
    }

    /**
     * Check if currently muted
     */
    public boolean isMuted() {
        return isMuted;
    }

    // ============================================================
    // MUTE / UNMUTE
    // ============================================================

    /**
     * Refresh sound card setting from SharedPreferences
     */
    private void refreshSoundCard() {
        SharedPreferences audioPrefs = context.getSharedPreferences("gsm_audio_config", Context.MODE_PRIVATE);
        int newCard = audioPrefs.getInt("card", 0);
        if (newCard != soundCard) {
            Log.d(TAG, "Sound card changed: " + soundCard + " -> " + newCard);
            soundCard = newCard;
        }
    }

    /**
     * Mute all controls (speaker + microphone) for current preset.
     * Called by watchdog when call is active.
     */
    public synchronized void muteAll() {
        if (isMuted) {
            return;  // Already muted
        }

        // Refresh sound card in case it changed
        refreshSoundCard();

        Log.d(TAG, "Muting all controls (preset: " + currentPreset + ")");
        originalEnumValues.clear();
        originalIntValues.clear();

        if (PRESET_CUSTOM.equals(currentPreset)) {
            // Custom preset: read controls from SharedPreferences
            muteCustomControls();
        } else {
            // Device preset: use predefined controls
            DevicePreset preset = PRESETS.get(currentPreset);
            if (preset == null) {
                Log.w(TAG, "Unknown preset: " + currentPreset);
                return;
            }
            mutePresetControls(preset);
        }

        isMuted = true;
    }

    /**
     * Mute controls for a device preset
     */
    private void mutePresetControls(DevicePreset preset) {
        // Mute speaker controls (ENUM -> ZERO)
        // Always try to set, even if we can't read current value
        for (String control : preset.speakerControls) {
            String original = readEnumControl(control);
            if (original != null && !original.isEmpty()) {
                originalEnumValues.put(control, original);
            }
            setEnumControl(control, "ZERO");
            Log.d(TAG, "Muted speaker: " + control + " (was: " + original + ")");
        }

        // Mute mic volume controls (INT -> 0)
        for (String control : preset.micVolumeControls) {
            int original = readIntControl(control);
            if (original >= 0) {
                originalIntValues.put(control, original);
            }
            setIntControl(control, 0);
            Log.d(TAG, "Muted mic volume: " + control + " (was: " + original + ")");
        }

        // Mute mic routing controls (ENUM -> ZERO)
        for (String control : preset.micRoutingControls) {
            String original = readEnumControl(control);
            if (original != null && !original.isEmpty()) {
                originalEnumValues.put(control, original);
            }
            setEnumControl(control, "ZERO");
            Log.d(TAG, "Muted mic routing: " + control + " (was: " + original + ")");
        }
    }

    /**
     * Mute controls from custom configuration (checkbox + manual).
     */
    private void muteCustomControls() {
        GatewayConfig config = GatewayConfig.getInstance();
        java.util.Set<String> controls = config.getAllMuteControls();

        if (controls.isEmpty()) {
            Log.w(TAG, "Custom preset but no controls configured");
            return;
        }

        for (String control : controls) {
            control = control.trim();
            if (control.isEmpty()) continue;

            if (control.contains(" Volume")) {
                // INT control - set to 0
                int original = readIntControl(control);
                if (original >= 0) {
                    originalIntValues.put(control, original);
                    setIntControl(control, 0);
                    Log.d(TAG, "Muted (custom): " + control + " (was: " + original + ")");
                }
            } else {
                // ENUM control (MUX, EAR_S, SPK) - set to ZERO
                String original = readEnumControl(control);
                if (original != null && !original.isEmpty()) {
                    originalEnumValues.put(control, original);
                    setEnumControl(control, "ZERO");
                    Log.d(TAG, "Muted (custom): " + control + " (was: " + original + ")");
                }
            }
        }
    }

    /**
     * Restore all controls to original values.
     * Called by watchdog when call ends.
     */
    public synchronized void unmuteAll() {
        if (!isMuted) {
            return;  // Not muted
        }

        Log.d(TAG, "Restoring all controls");

        // Restore ENUM controls
        for (Map.Entry<String, String> entry : originalEnumValues.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                setEnumControl(entry.getKey(), entry.getValue());
                Log.d(TAG, "Restored: " + entry.getKey() + " = " + entry.getValue());
            }
        }

        // Restore INT controls
        for (Map.Entry<String, Integer> entry : originalIntValues.entrySet()) {
            setIntControl(entry.getKey(), entry.getValue());
            Log.d(TAG, "Restored: " + entry.getKey() + " = " + entry.getValue());
        }

        originalEnumValues.clear();
        originalIntValues.clear();
        isMuted = false;
    }

    /**
     * Force mute all controls (called periodically by watchdog to combat Android re-routing)
     */
    public synchronized void enforceMute() {
        if (PRESET_CUSTOM.equals(currentPreset)) {
            // Custom preset: re-enforce all stored controls
            for (String control : originalEnumValues.keySet()) {
                setEnumControl(control, "ZERO");
            }
            for (String control : originalIntValues.keySet()) {
                setIntControl(control, 0);
            }
        } else {
            // Device preset: enforce ALL controls (speaker + mic)
            DevicePreset preset = PRESETS.get(currentPreset);
            if (preset == null) return;

            // Speaker controls
            for (String control : preset.speakerControls) {
                setEnumControl(control, "ZERO");
            }
            // Mic volume controls
            for (String control : preset.micVolumeControls) {
                setIntControl(control, 0);
            }
            // Mic routing controls
            for (String control : preset.micRoutingControls) {
                setEnumControl(control, "ZERO");
            }
        }
    }

    // ============================================================
    // Low-level mixer control access
    // ============================================================

    private void setEnumControl(String name, String value) {
        GsmAudioNative.setMixerControlEnum(soundCard, name, value);
    }

    private void setIntControl(String name, int value) {
        GsmAudioNative.setMixerControl(soundCard, name, value);
    }

    private String readEnumControl(String name) {
        // Read via tinymix
        try {
            String cmd = "su -c 'tinymix -D " + soundCard + " get \"" + name + "\"'";
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            if (line != null && !line.isEmpty()) {
                // Parse output like "EAR_S: ZERO >Switch" -> return current value
                // The current value has > prefix
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.startsWith(">")) {
                        return part.substring(1);
                    }
                }
                // Fallback: return last part
                if (parts.length > 1) {
                    return parts[parts.length - 1];
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read enum control " + name + ": " + e.getMessage());
        }
        return "";
    }

    private int readIntControl(String name) {
        try {
            String cmd = "su -c 'tinymix -D " + soundCard + " get \"" + name + "\"'";
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            if (line != null && !line.isEmpty()) {
                // Parse output like "DEC1 Volume: 84" -> return 84
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    try {
                        return Integer.parseInt(part);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read int control " + name + ": " + e.getMessage());
        }
        return -1;
    }

    // ============================================================
    // Device Preset class
    // ============================================================

    private static class DevicePreset {
        String description;
        String[] speakerControls;      // ENUM controls for speaker/earpiece
        String[] micVolumeControls;    // INT controls for mic volume
        String[] micRoutingControls;   // ENUM controls for mic routing

        DevicePreset(String description, String[] speaker, String[] micVol, String[] micRoute) {
            this.description = description;
            this.speakerControls = speaker;
            this.micVolumeControls = micVol;
            this.micRoutingControls = micRoute;
        }
    }
}
