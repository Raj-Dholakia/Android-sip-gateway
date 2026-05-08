package org.onetwoone.gateway;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.pjsip.pjsua2.*;

import android.media.AudioFormat;
import android.media.AudioRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom AudioMediaPort for bridging GSM call audio to SIP.
 *
 * KERNEL-NATIVE APPROACH (Option C):
 * - Capture (GSM→SIP): Native tinyalsa pcm_read() via VOC_REC mixer route
 *   (bypasses Android AudioRecord entirely — direct kernel PCM access)
 * - Playback (SIP→GSM): Native tinyalsa pcm_write() via Incall_Music mixer route
 *
 * Both directions use tinyalsa (libgsm_audio.so JNI) for direct kernel access.
 * No Android audio framework involvement at all.
 *
 * KEY FIX: Sets VOC_REC_UL first (alone) to avoid the q6voice.c race condition
 * where setting DL then UL causes a stop/restart gap with silence.
 * See: kernel-source/techpack/audio/dsp/q6voice.c lines 5820-5900
 *
 * PCM Device Map (from kernel analysis):
 *   MultiMedia1 = PCM device 0 (VOC_REC capable)
 *   MultiMedia2 = PCM device 1 (Incall_Music capable)
 *   MultiMedia4 = PCM device 3 (VOC_REC capable)
 *   MultiMedia8 = PCM device 7 (VOC_REC capable)
 *   MultiMedia9 = PCM device 8 (VOC_REC + Incall_Music capable)
 *
 * Default config: capture on MM1 (device 0), playback on MM2 (device 1)
 * This uses separate PCM devices to avoid contention.
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
    private int captureDevice = 27;     // PCM device 27 = MultiMedia9 (VOC_REC capable, HAL doesn't claim it)
    private int playbackDevice = 1;     // PCM device 1 = MultiMedia2 (Incall_Music capable)
    private String captureRoute = "MultiMedia9";   // Mixer route for capture (VOC_REC)
    private String playbackRoute = "MultiMedia2";  // Mixer route for playback (Incall_Music)

    // Capture mode: which VOC_REC to enable
    // "UL" = uplink only (caller's voice), "DL" = downlink only, "BOTH" = both
    private String vocRecMode = "UL";

    // DSP trigger: dummy AudioRecord to send VSS_IRECORD_CMD_START to the ADSP
    // Without this, VOC_REC mixer routes are set but DSP never starts recording.
    // We don't read from this — actual audio comes from tinyalsa PCM device.
    private AudioRecord dspTriggerRecord = null;
    private static final int AUDIO_SOURCE_VOICE_UPLINK = 2;

    // Microphone mute controls (device-specific) - can mute multiple DECs
    private List<String> micMuteControls = new ArrayList<>();
    private Map<String, Integer> micOriginalValues = new HashMap<>();
    private Map<String, String> micOriginalEnumValues = new HashMap<>();

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
    private long consecutiveCaptureErrors = 0;
    private static final long MAX_CONSECUTIVE_ERRORS_LOG = 50;  // Log after this many consecutive errors

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
        captureDevice = prefs.getInt("capture_device", 27);
        playbackDevice = prefs.getInt("playback_device", 1);
        captureRoute = prefs.getString("capture_route", "MultiMedia9");
        playbackRoute = prefs.getString("playback_route", "MultiMedia2");
        vocRecMode = prefs.getString("voc_rec_mode", "UL");

        // Backward compat: read old keys, but prefer new defaults
        if (prefs.contains("multimedia_route") && !prefs.contains("capture_route")) {
            captureRoute = prefs.getString("multimedia_route", "MultiMedia9");
        }

        String decList = prefs.getString("mic_mute_decs", "");
        micMuteControls.clear();
        if (!decList.isEmpty()) {
            for (String dec : decList.split(",")) {
                micMuteControls.add(dec.trim());
            }
        }

        Log.i(TAG, "Config loaded [KERNEL-NATIVE]: card=" + card +
              ", capture=device" + captureDevice + "(" + captureRoute + ")" +
              ", playback=device" + playbackDevice + "(" + playbackRoute + ")" +
              ", vocRecMode=" + vocRecMode +
              ", micMuteDECs=" + micMuteControls);
    }

    /**
     * Save device configuration to SharedPreferences
     */
    public void saveConfig(int card, int captureDevice, int playbackDevice,
                           String captureRoute, String playbackRoute,
                           String vocRecMode) {
        this.card = card;
        this.captureDevice = captureDevice;
        this.playbackDevice = playbackDevice;
        this.captureRoute = captureRoute;
        this.playbackRoute = playbackRoute;
        this.vocRecMode = vocRecMode;

        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putInt("card", card);
        editor.putInt("capture_device", captureDevice);
        editor.putInt("playback_device", playbackDevice);
        editor.putString("capture_route", captureRoute);
        editor.putString("playback_route", playbackRoute);
        editor.putString("voc_rec_mode", vocRecMode);
        editor.apply();

        Log.i(TAG, "Config saved [KERNEL-NATIVE]: card=" + card +
              ", capture=" + captureDevice + "(" + captureRoute + ")" +
              ", playback=" + playbackDevice + "(" + playbackRoute + ")");
    }

    // Overloaded for backward compat
    public void saveConfig(int card, int captureDevice, int playbackDevice, String multimediaRoute) {
        String pbRoute = "MultiMedia" + (playbackDevice + 1);
        saveConfig(card, captureDevice, playbackDevice, multimediaRoute, pbRoute, "UL");
    }

    /**
     * Initialize native audio
     */
    public boolean initialize() {
        Log.d(TAG, "Initializing GsmAudioPort [KERNEL-NATIVE mode]...");
        Log.d(TAG, "  Capture:  tinyalsa pcm_read()  → device " + captureDevice + " (" + captureRoute + " VOC_REC)");
        Log.d(TAG, "  Playback: tinyalsa pcm_write() → device " + playbackDevice + " (" + playbackRoute + " Incall_Music)");

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
     * Reads from tinyalsa capture PCM (pure kernel, no AudioRecord)
     */
    @Override
    public void onFrameRequested(MediaFrame frame) {
        framesRequested++;

        try {
            ByteVector buf = frame.getBuf();
            buf.clear();

            if (isCapturing.get() && GsmAudioNative.isOpen()) {
                // Read from native tinyalsa (direct kernel PCM)
                int bytesRead = GsmAudioNative.readFrame(captureBuffer);

                if (bytesRead == FRAME_SIZE) {
                    for (byte b : captureBuffer) {
                        buf.add((short) (b & 0xFF));
                    }
                    frame.setSize(FRAME_SIZE);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO);
                    consecutiveCaptureErrors = 0;
                } else {
                    captureErrors++;
                    consecutiveCaptureErrors++;
                    for (int i = 0; i < FRAME_SIZE; i++) buf.add((short) 0);
                    frame.setSize(FRAME_SIZE);
                    frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);

                    if (consecutiveCaptureErrors == MAX_CONSECUTIVE_ERRORS_LOG) {
                        Log.w(TAG, "Capture: " + MAX_CONSECUTIVE_ERRORS_LOG + " consecutive read errors — PCM device may not be providing data");
                    }
                }
            } else {
                // Not capturing or not open - send silence
                for (int i = 0; i < FRAME_SIZE; i++) buf.add((short) 0);
                frame.setSize(FRAME_SIZE);
                frame.setType(pjmedia_frame_type.PJMEDIA_FRAME_TYPE_NONE);
            }

            // Log every 500 frames (~10 seconds)
            if (framesRequested % 500 == 0) {
                Log.d(TAG, "onFrameRequested: " + framesRequested + " frames, errors=" + captureErrors +
                      ", consecutiveErr=" + consecutiveCaptureErrors + ", mode=KERNEL-NATIVE");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onFrameRequested: " + e.getMessage());
        }
    }

    /**
     * PJSIP callback: RECEIVED audio from SIP peer (SIP → GSM direction)
     * Writes via native tinyalsa (direct kernel PCM)
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
     * KERNEL-NATIVE: Opens tinyalsa for BOTH capture AND playback.
     * No AudioRecord. No Android audio framework.
     *
     * Mixer setup order matters to avoid q6voice.c race condition:
     * 1. Set VOC_REC_UL first (if needed)
     * 2. Then set VOC_REC_DL (if needed) — DSP will stop+restart as BOTH
     * 3. THEN open PCM devices (after mixer routes are established)
     */
    public void startCapture() {
        if (isCapturing.get()) {
            Log.w(TAG, "Already capturing");
            return;
        }

        Log.d(TAG, "Starting KERNEL-NATIVE audio (tinyalsa capture + tinyalsa playback)...");

        // Step 1: Setup mixer controls (order matters for DSP race condition!)
        boolean mixerOk = setupMixer();
        if (!mixerOk) {
            Log.w(TAG, "Mixer setup failed, audio may not work");
        }

        // Step 2: Trigger DSP voice recording via dummy AudioRecord
        // This sends VSS_IRECORD_CMD_START to the ADSP voice processor.
        // Without it, VOC_REC mixer routes are connected but DSP never
        // actually taps the voice call audio stream.
        startDspTrigger();

        // Step 3: Small delay to let DSP stabilize
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // Step 4: Open tinyalsa for BOTH capture and playback
        // Open capture and playback separately for better error reporting
        // Capture first (this is the one that might fail if HAL locks the device)
        boolean captureOk = GsmAudioNative.openCapture(
            card, captureDevice,
            SAMPLE_RATE, CHANNELS, BITS,
            PERIOD_SIZE, PERIOD_COUNT
        );

        if (!captureOk) {
            Log.e(TAG, "CAPTURE FAILED: PCM device " + captureDevice +
                  " (" + captureRoute + ") — locked by HAL or wrong device number");
            Log.e(TAG, "Try a different capture device: 3(MM4), 7(MM8), or 8(MM9)");
        } else {
            Log.i(TAG, "✓ Capture PCM opened: device " + captureDevice + " (" + captureRoute + ")");
        }

        // Playback
        boolean playbackOk = GsmAudioNative.openPlayback(
            card, playbackDevice,
            SAMPLE_RATE, CHANNELS, BITS,
            PERIOD_SIZE, PERIOD_COUNT
        );

        if (!playbackOk) {
            Log.e(TAG, "PLAYBACK FAILED: PCM device " + playbackDevice +
                  " (" + playbackRoute + ")");
        } else {
            Log.i(TAG, "✓ Playback PCM opened: device " + playbackDevice + " (" + playbackRoute + ")");
        }

        if (captureOk && playbackOk) {
            Log.i(TAG, "✓✓ BOTH capture and playback opened successfully");
        } else if (!captureOk && playbackOk) {
            Log.w(TAG, "⚠ Playback works but capture failed — caller won't be heard");
        } else if (!captureOk && !playbackOk) {
            Log.e(TAG, "✗✗ BOTH capture and playback FAILED!");
        }

        isCapturing.set(true);
        Log.d(TAG, "Kernel-native audio started: capture=tinyalsa(device" + captureDevice + "), " +
              "playback=tinyalsa(device" + playbackDevice + ")");
    }

    /**
     * Stop audio capture/playback
     */
    public void stopCapture() {
        Log.d(TAG, "Stopping kernel-native audio...");

        isCapturing.set(false);

        // Stop DSP trigger
        stopDspTrigger();

        // Close ALL tinyalsa devices
        GsmAudioNative.close();

        // Teardown mixer
        teardownMixer();

        Log.d(TAG, "Kernel-native audio stopped. Stats: requested=" + framesRequested +
              ", received=" + framesReceived +
              ", captureErr=" + captureErrors + ", playbackErr=" + playbackErrors);

        // Reset statistics
        framesRequested = 0;
        framesReceived = 0;
        captureErrors = 0;
        playbackErrors = 0;
        consecutiveCaptureErrors = 0;
    }

    public void stop() {
        stopCapture();
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }

    // ========== DSP Trigger ==========

    /**
     * Start a dummy AudioRecord with VOICE_UPLINK source.
     * 
     * WHY: On Qualcomm, the voice call audio flows through the ADSP.
     * Setting VOC_REC_UL via tinymix only connects the ALSA mixer route.
     * The DSP won't actually start tapping the voice stream until
     * an AudioRecord with VOICE_UPLINK/VOICE_CALL source is opened,
     * which triggers AudioFlinger → AudioHAL → q6voice → VSS_IRECORD_CMD_START.
     *
     * We DON'T read audio from this AudioRecord — the data quality is poor
     * (HAL processing, echo gate, etc). Actual audio comes from tinyalsa
     * pcm_read() on the VOC_REC PCM device, which gets the raw digital stream.
     *
     * Think of it as: AudioRecord = ignition key, tinyalsa = the engine.
     */
    private void startDspTrigger() {
        Log.d(TAG, "Starting DSP trigger (dummy AudioRecord VOICE_UPLINK)...");

        try {
            int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            );

            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "DSP trigger: getMinBufferSize failed");
                return;
            }

            // Try VOICE_UPLINK first (triggers UL recording in DSP)
            dspTriggerRecord = new AudioRecord(
                AUDIO_SOURCE_VOICE_UPLINK,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, FRAME_SIZE * 4)
            );

            if (dspTriggerRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "DSP trigger: VOICE_UPLINK failed to init, trying VOICE_CALL(4)...");
                dspTriggerRecord.release();

                // Fallback: VOICE_CALL source
                dspTriggerRecord = new AudioRecord(
                    4, // VOICE_CALL
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, FRAME_SIZE * 4)
                );

                if (dspTriggerRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "DSP trigger: VOICE_CALL also failed — DSP may not record");
                    dspTriggerRecord.release();
                    dspTriggerRecord = null;
                    return;
                }
            }

            dspTriggerRecord.startRecording();

            if (dspTriggerRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                Log.i(TAG, "✓ DSP trigger started (AudioRecord VOICE_UPLINK) — DSP should now tap voice stream");
            } else {
                Log.e(TAG, "DSP trigger: startRecording() didn't start");
                dspTriggerRecord.release();
                dspTriggerRecord = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "DSP trigger failed: " + e.getMessage());
            if (dspTriggerRecord != null) {
                try { dspTriggerRecord.release(); } catch (Exception ignored) {}
                dspTriggerRecord = null;
            }
        }
    }

    /**
     * Stop the DSP trigger AudioRecord.
     */
    private void stopDspTrigger() {
        if (dspTriggerRecord != null) {
            Log.d(TAG, "Stopping DSP trigger...");
            try {
                dspTriggerRecord.stop();
            } catch (Exception e) {
                Log.w(TAG, "DSP trigger stop error: " + e.getMessage());
            }
            dspTriggerRecord.release();
            dspTriggerRecord = null;
            Log.d(TAG, "DSP trigger stopped");
        }
    }

    // ========== Mixer Controls ==========

    /**
     * Setup mixer routes for kernel-native capture and playback.
     *
     * CRITICAL: Order matters due to q6voice.c race condition.
     *
     * The DSP voice recording state machine in q6voice.c has this behavior:
     * - If DL is set first, then UL comes in: DSP STOPS recording, restarts as BOTH
     * - This causes a gap with silence (rawCapRMS=0)
     *
     * FIX: Set UL first (alone), then set DL. The DSP correctly transitions
     * from UPLINK → stop → BOTH without the problematic gap.
     *
     * Or better: if you only need caller's voice, set ONLY UL and never touch DL.
     */
    private boolean setupMixer() {
        Log.d(TAG, "Setting up mixer [KERNEL-NATIVE] capture=" + captureRoute +
              ", playback=" + playbackRoute + ", vocRecMode=" + vocRecMode + "...");

        boolean ok = true;

        // === Capture routes (VOC_REC) — ORDER MATTERS ===
        switch (vocRecMode.toUpperCase()) {
            case "UL":
                // Uplink only (caller's voice) — simplest, avoids race entirely
                ok &= GsmAudioNative.setMixerControl(card, captureRoute + " Mixer VOC_REC_UL", 1);
                Log.d(TAG, "VOC_REC: UL only (caller's voice, no race condition)");
                break;

            case "DL":
                // Downlink only (AI's voice loopback — rare, for testing)
                ok &= GsmAudioNative.setMixerControl(card, captureRoute + " Mixer VOC_REC_DL", 1);
                Log.d(TAG, "VOC_REC: DL only");
                break;

            case "BOTH":
                // Both directions — set UL FIRST to avoid race condition
                ok &= GsmAudioNative.setMixerControl(card, captureRoute + " Mixer VOC_REC_UL", 1);
                // Small delay to let DSP register UL before we add DL
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                ok &= GsmAudioNative.setMixerControl(card, captureRoute + " Mixer VOC_REC_DL", 1);
                Log.d(TAG, "VOC_REC: BOTH (UL set first to avoid q6voice race)");
                break;

            default:
                Log.w(TAG, "Unknown vocRecMode '" + vocRecMode + "', defaulting to UL");
                ok &= GsmAudioNative.setMixerControl(card, captureRoute + " Mixer VOC_REC_UL", 1);
                break;
        }

        // === Playback routes (Incall_Music) ===
        ok &= GsmAudioNative.setMixerControl(card, "Incall_Music Audio Mixer " + playbackRoute, 1);
        // Also enable Incall_Music_2 for SIM2 support
        GsmAudioNative.setMixerControl(card, "Incall_Music_2 Audio Mixer " + playbackRoute, 1);

        Log.d(TAG, "Mixer routes: capture=" + captureRoute + " VOC_REC_" + vocRecMode +
              ", playback=Incall_Music " + playbackRoute);

        // === Mute configured microphone controls (prevent echo/feedback) ===
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
            Log.d(TAG, "Mixer setup OK [KERNEL-NATIVE]");
        } else {
            Log.w(TAG, "Mixer setup incomplete — some controls may not exist on this device");
        }

        return ok;
    }

    private void teardownMixer() {
        Log.d(TAG, "Tearing down mixer...");

        // Teardown capture routes
        GsmAudioNative.setMixerControl(card, captureRoute + " Mixer VOC_REC_UL", 0);
        GsmAudioNative.setMixerControl(card, captureRoute + " Mixer VOC_REC_DL", 0);

        // Teardown playback routes
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
            java.io.File tinymixFile = new java.io.File(context.getFilesDir(), "tinymix");
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
