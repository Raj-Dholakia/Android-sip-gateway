package org.onetwoone.gateway.ui;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.onetwoone.gateway.GsmAudioNative;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages audio device detection and selection.
 *
 * Handles:
 * - Detecting available sound cards
 * - Enumerating capture/playback PCM devices
 * - Parsing device numbers from device strings
 */
public class AudioDeviceManager {
    private static final String TAG = "AudioDeviceManager";

    /**
     * Holds the current list of audio devices.
     */
    public static class AudioDevices {
        public List<String> cards = new ArrayList<>();
        public List<String> captureDevices = new ArrayList<>();
        public List<String> playbackDevices = new ArrayList<>();

        public int getCardCount() {
            return cards.size();
        }
    }

    private final MutableLiveData<AudioDevices> devices = new MutableLiveData<>(new AudioDevices());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AudioDeviceManager() {
    }

    public LiveData<AudioDevices> getDevices() {
        return devices;
    }

    /**
     * Refresh device lists for the specified sound card.
     * Runs on background thread and updates LiveData.
     *
     * @param cardNumber The sound card to enumerate devices for
     */
    public void refreshDevices(int cardNumber) {
        executor.execute(() -> {
            AudioDevices result = new AudioDevices();

            // Get card count
            int cardCount = GsmAudioNative.getCardCount();
            if (cardCount <= 0) cardCount = 1;

            for (int i = 0; i < cardCount; i++) {
                result.cards.add("Card " + i);
            }

            // Get capture devices for selected card
            String[] captureArr = GsmAudioNative.getPcmDevices(cardNumber, true);
            if (captureArr != null && captureArr.length > 0) {
                result.captureDevices.addAll(Arrays.asList(captureArr));
            }
            if (result.captureDevices.isEmpty()) {
                result.captureDevices.add("No capture devices");
            }

            // Get playback devices for selected card
            String[] playbackArr = GsmAudioNative.getPcmDevices(cardNumber, false);
            if (playbackArr != null && playbackArr.length > 0) {
                result.playbackDevices.addAll(Arrays.asList(playbackArr));
            }
            if (result.playbackDevices.isEmpty()) {
                result.playbackDevices.add("No playback devices");
            }

            Log.d(TAG, "Found " + result.captureDevices.size() + " capture, " +
                  result.playbackDevices.size() + " playback devices for card " + cardNumber);

            devices.postValue(result);
        });
    }

    /**
     * Parse device number from a device string.
     *
     * @param deviceString Format: "36: msm-pcm-voice-v2"
     * @return Device number (e.g., 36) or 0 if parsing fails
     */
    public static int parseDeviceNumber(String deviceString) {
        if (deviceString == null || deviceString.isEmpty()) {
            return 0;
        }

        try {
            int colonPos = deviceString.indexOf(':');
            if (colonPos > 0) {
                return Integer.parseInt(deviceString.substring(0, colonPos).trim());
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse device number from: " + deviceString);
        }
        return 0;
    }

    /**
     * Find the index of a device with the given device number in the list.
     *
     * @param devices    List of device strings
     * @param deviceNum  Device number to find
     * @return Index in the list, or -1 if not found
     */
    public static int findDeviceIndex(List<String> devices, int deviceNum) {
        for (int i = 0; i < devices.size(); i++) {
            if (parseDeviceNumber(devices.get(i)) == deviceNum) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Shutdown the executor service.
     */
    public void shutdown() {
        executor.shutdown();
    }
}
