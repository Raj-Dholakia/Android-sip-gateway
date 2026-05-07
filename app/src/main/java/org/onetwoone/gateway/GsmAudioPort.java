package org.onetwoone.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
 * 
 * HYBRID APPROACH:
 * - Capture (GSM→SIP): Android AudioRecord with VOICE_DOWNLINK source
 *   (reads through the HAL, which owns the PCM device exclusively on Android 16+)
 * - Playback (SIP→GSM): Native tinyalsa pcm_write() via Incall_Music mixer route
 *   (this path works fine - HAL doesn't lock playback MultiMedia2)
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

    // AudioRecord audio sources to try for voice call capture
    // VOICE_DOWNLINK = 3, VOICE_CALL = 4, VOICE_UPLINK = 2
    private static final int AUDIO_SOURCE_VOICE_DOWNLINK = 3;
    private static final int AUDIO_SOURCE_VOICE_CALL = 4;
    private static final int AUDIO_SOURCE_VOICE_UPLINK = 2;

    // Configurable device parameters (loaded from SharedPreferences)
    private int card = 0;
    private int captureDevice = 0;      // PCM device for VOC_REC capture (used by tinyalsa fallback)
    private int playbackDevice = 0;     // PCM device for Incall_Music playback
    private String multimediaRoute = "MultiMedia1";  // Mixer route name (capture/VOC_REC)
    private String playbackRoute = "";  // Separate playback route (empty = same as multimediaRoute)

    // Microphone mute controls (device-specific) - can mute multiple DECs
    private List<String> micMuteControls = new ArrayList<>();
    private Map<String, Integer> micOriginalValues = new HashMap<>();
    private Map<String, String> micOriginalEnumValues = new HashMap<>();

    private Context context;
    private AtomicBoolean isCapturing = new AtomicBoolean(false);
    private AtomicBoolean isPortCreated = new AtomicBoolean(false);

    // AudioRecord for capture (GSM→SIP)
    private AudioRecord audioRecord;
    private boolean captureViaAudioRecord = false;

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
        playbackRoute = prefs.getString("playback_route", "");
        if (playbackRoute.isEmpty()) {
            if (captureDevice != playbackDevice) {
                playbackRoute = "MultiMedia" + (playbackDevice + 1);
                Log.i(TAG, "Auto-derived playback route: " + playbackRoute + " (device " + playbackDevice + ")");
            } else {
                playbackRoute = multimediaRoute;
            }
        }

        String decList = prefs.getString("mic_mute_decs", "");
        micMuteControls.clear();
        if (!decList.isEmpty()) {
            for (String dec : decList.split(",")) {
                micMuteControls.add(dec.trim());
            }
        }

        Log.i(TAG, "Config loaded: card=" + card + ", capture=" + captureDevice +
              ", playback=" + playbackDevice + ", captureRoute=" + multimediaRoute +
              ", playbackRoute=" + playbackRoute +
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
        Log.d(TAG, "Initializing GsmAudioPort (hybrid mode: AudioRecord capture + tinyalsa playback)...");

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
     * Try to create AudioRecord with a given audio source.
     * Returns the AudioRecord if successful, null otherwise.
     */
    private AudioRecord tryCreateAudioRecord(int audioSource, String sourceName) {
        try {
            int minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );
            if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "AudioRecord.getMinBufferSize failed for " + sourceName);
                return null;
            }

            // Use at least 4x frame size for buffer
            int bufferSize = Math.max(minBufSize, FRAME_SIZE * 4);

            AudioRecord record = new AudioRecord(
                audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );

            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "AudioRecord created with source " + sourceName + " (" + audioSource + "), bufSize=" + bufferSize);
                return record;
            } else {
                Log.w(TAG, "AudioRecord failed to initialize with source " + sourceName);
                record.release();
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord creation failed for " + sourceName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Start AudioRecord capture for GSM→SIP direction.
     * Tries VOICE_DOWNLINK first, then VOICE_CALL, then VOICE_UPLINK.
     */
    private boolean startAudioRecordCapture() {
        Log.i(TAG, "Starting AudioRecord capture (hybrid mode)...");

        // Try VOICE_DOWNLINK first (captures far-end voice only)
        audioRecord = tryCreateAudioRecord(AUDIO_SOURCE_VOICE_DOWNLINK, "VOICE_DOWNLINK");

        // Fallback to VOICE_CALL (captures both directions)
        if (audioRecord == null) {
            audioRecord = tryCreateAudioRecord(AUDIO_SOURCE_VOICE_CALL, "VOICE_CALL");
        }

        // Fallback to VOICE_UPLINK (captures local mic - not ideal but might work)
        if (audioRecord == null) {
            audioRecord = tryCreateAudioRecord(AUDIO_SOURCE_VOICE_UPLINK, "VOICE_UPLINK");
        }

        if (audioRecord == null) {
            Log.e(TAG, "All AudioRecord sources failed! Cannot capture voice call audio.");
            return false;
        }

        try {
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                captureViaAudioRecord = true;
                Log.i(TAG, "AudioRecord capture STARTED successfully");
                return true;
            } else {
                Log.e(TAG, "AudioRecord.startRecording() did not start recording");
                audioRecord.release();
                audioRecord = null;
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord.startRecording() failed: " + e.getMessage());
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
            return false;
        }
    }

    /**
     * Stop AudioRecord capture
     */
    private void stopAudioRecordCapture() {
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord.stop() error: " + e.getMessage());
            }
            audioRecord.release();
            audioRecord = null;
            captureViaAudioRecord = false;
            Log.i(TAG, "AudioRecord capture stopped");
        }
    }

    /**
     * PJSIP callback: Need audio to SEND to SIP peer (GSM → SIP direction)
     * Reads from AudioRecord (hybrid mode)
     */
    @Override
    public void onFrameRequested(MediaFrame frame) {
        framesRequested++;

        try {
            ByteVector buf = frame.getBuf();
            buf.clear();

            if (isCapturing.get() && captureViaAudioRecord && audioRecord != null) {
                // Read from AudioRecord (through Android HAL)
                int bytesRead = audioRecord.read(captureBuffer, 0, FRAME_SIZE);

                if (bytesRead == FRAME_SIZE) {
                    for (byte b : captureBuffer) {
                        buf.add((short) (b & 0xFF));
                    }
                    frame.setSize(FRAME_SIZE);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
                } else {
                    captureErrors++;
                    for (int i = 0; i < FRAME_SIZE; i++) buf.add((short) 0);
                    frame.setSize(FRAME_SIZE);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
                }
            } else if (isCapturing.get() && GsmAudioNative.isOpen()) {
                // Fallback: read from native tinyalsa (if capture PCM was opened)
                int bytesRead = GsmAudioNative.readFrame(captureBuffer);

                if (bytesRead == FRAME_SIZE) {
                    for (byte b : captureBuffer) {
                        buf.add((short) (b & 0xFF));
                    }
                    frame.setSize(FRAME_SIZE);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
                } else {
                    captureErrors++;
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
                Log.d(TAG, "onFrameRequested: " + framesRequested + " frames, errors=" + captureErrors +
                      ", captureMode=" + (captureViaAudioRecord ? "AudioRecord" : "tinyalsa"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameRequested: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: RECEIVED audio from SIP peer (SIP → GSM direction)
     * Writes via native tinyalsa (unchanged)
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
                for (int i = 0; i < size; i++) {
                    playbackBuffer[i] = (byte) (buf.get(i) & 0xFF);
                }

                int bytesWritten = GsmAudioNative.writeFrame(playbackBuffer);
                if (bytesWritten < 0) {
                    playbackErrors++;
                }
            }

            if (framesReceived % 500 == 0) {
                Log.d(TAG, "onFrameReceived: " + framesReceived + " frames, errors=" + playbackErrors);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameReceived: " + e.getMessage());
        }
    }

    /**
     * Start audio capture/playback (when GSM call becomes active)
     * 
     * HYBRID: Opens AudioRecord for capture + tinyalsa for playback only
     */
    public void startCapture() {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing");
            return;
        }

        Log.d(TAG, "Starting HYBRID audio (AudioRecord capture + tinyalsa playback)...");

        // Setup mixer controls for playback (Incall_Music)
        boolean mixerOk = setupMixer();
        if (!mixerOk) {
            Log.w(TAG, "Mixer setup failed, audio may not work");
        }

        // Open tinyalsa for PLAYBACK ONLY (using openPlayback)
        boolean playbackOpened = GsmAudioNative.openPlayback(
            card, playbackDevice,
            SAMPLE_RATE, CHANNELS, BITS,
            PERIOD_SIZE, PERIOD_COUNT
        );

        if (!playbackOpened) {
            Log.e(TAG, "Failed to open tinyalsa playback device " + playbackDevice + "!");
            // Try the full open (which now handles capture failure gracefully)
            playbackOpened = GsmAudioNative.open(
                card, captureDevice, playbackDevice,
                SAMPLE_RATE, CHANNELS, BITS,
                PERIOD_SIZE, PERIOD_COUNT
            );
            if (!playbackOpened) {
                Log.e(TAG, "Failed to open ANY playback device!");
            }
        } else {
            Log.i(TAG, "Tinyalsa playback opened on device " + playbackDevice);
        }

        // Start AudioRecord capture (through Android HAL)
        boolean captureStarted = startAudioRecordCapture();
        if (!captureStarted) {
            Log.w(TAG, "AudioRecord capture failed - GSM→SIP will be silent");
        }

        isCapturing.set(true);
        Log.d(TAG, "Hybrid audio started: capture=" + (captureStarted ? "AudioRecord" : "FAILED") +
              ", playback=" + (playbackOpened ? "tinyalsa" : "FAILED"));
    }

    /**
     * Stop audio capture/playback
     */
    public void stopCapture() {
        Log.d(TAG, "Stopping hybrid audio...");

        isCapturing.set(false);

        // Stop AudioRecord capture
        stopAudioRecordCapture();

        // Close tinyalsa playback
        GsmAudioNative.closePlayback();

        // Teardown mixer
        teardownMixer();

        Log.d(TAG, "Hybrid audio stopped. Stats: requested=" + framesRequested +
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

        // Enable VOC_REC capture route (still needed even with AudioRecord on some devices)
        ok &= GsmAudioNative.setMixerControl(card, multimediaRoute + " Mixer VOC_REC_DL", 1);

        // Enable Incall_Music playback on PLAYBACK route
        ok &= GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + playbackRoute, 1);
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + playbackRoute, 1);

        Log.d(TAG, "Mixer routes: capture=" + multimediaRoute + " Mixer VOC_REC_DL, playback=Incall_Music Audio Mixer " + playbackRoute);

        // Mute configured controls
        if (!micMuteControls.isEmpty()) {
            micOriginalValues.clear();
            micOriginalEnumValues.clear();
            for (String decControl : micMuteControls) {
                if (decControl.contains(" Volume")) {
                    int originalValue = readMixerControlValue(decControl);
                    micOriginalValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControl(card, decControl, 0);
                    Log.d(TAG, "Muted: " + decControl + " = 0 (original=" + originalValue + ")");
                } else if (decControl.contains(" MUX")) {
                    String originalValue = readMixerControlEnum(decControl);
                    micOriginalEnumValues.put(decControl, originalValue);
                    GsmAudioNative.setMixerControlEnum(card, decControl, "ZERO");
                    Log.d(TAG, "Muted: " + decControl + " = ZERO (original=" + originalValue + ")");
                } else if (decControl.equals("EAR_S") || decControl.equals("SPK")) {
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
        GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + playbackRoute, 0);
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + playbackRoute, 0);

        // Restore ALL muted controls
        for (String decControl : micMuteControls) {
            if (decControl.contains(" Volume")) {
                Integer originalValue = micOriginalValues.get(decControl);
                if (originalValue != null) {
                    GsmAudioNative.setMixerControl(card, decControl, originalValue);
                    Log.d(TAG, "Restored: " + decControl + " = " + originalValue);
                }
            } else if (decControl.contains(" MUX") || decControl.equals("EAR_S") || decControl.equals("SPK")) {
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

    private int readMixerControlValue(String controlName) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "tinymix -D " + card + " get \"" + controlName + "\""
            });
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            if (line != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0) {
                    return Integer.parseInt(parts[0]);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read mixer control " + controlName + ": " + e.getMessage());
        }
        return 84;
    }

    private String readMixerControlEnum(String controlName) {
        try {
            File tinymixFile = new File(context.getFilesDir(), "tinymix");
            Process p = Runtime.getRuntime().exec(new String[]{
                tinymixFile.getAbsolutePath(), "-D", String.valueOf(card), "get", controlName
            });
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            p.waitFor();

            if (line != null) {
                return line.trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read mixer control ENUM " + controlName + ": " + e.getMessage());
        }
        return "";
    }

}
