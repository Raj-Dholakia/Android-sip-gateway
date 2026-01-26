/*
 * GSM Audio JNI - Native tinyalsa integration for GSM-SIP Gateway
 *
 * Replaces tinycap/tinyplay processes with direct ALSA access.
 * All parameters are configurable - no hardcoded device paths.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <errno.h>

#include "tinyalsa/include/tinyalsa/asoundlib.h"

#define TAG "GsmAudioNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* Audio context - holds all state */
struct gsm_audio_ctx {
    struct pcm *capture_pcm;
    struct pcm *playback_pcm;
    struct mixer *mixer;

    unsigned int card;
    unsigned int capture_device;
    unsigned int playback_device;
    unsigned int sample_rate;
    unsigned int channels;
    unsigned int bits;
    unsigned int period_size;
    unsigned int period_count;

    int is_open;
    pthread_mutex_t lock;
};

static struct gsm_audio_ctx *g_ctx = NULL;

/* Helper: Get PCM format from bits */
static enum pcm_format bits_to_format(unsigned int bits) {
    switch (bits) {
        case 32: return PCM_FORMAT_S32_LE;
        case 24: return PCM_FORMAT_S24_LE;
        case 16:
        default: return PCM_FORMAT_S16_LE;
    }
}

/*
 * Open audio devices
 *
 * @param card          Sound card number (usually 0)
 * @param captureDevice PCM device for capture (VOC_REC)
 * @param playbackDevice PCM device for playback (Incall_Music)
 * @param sampleRate    Sample rate in Hz (8000 for GSM)
 * @param channels      Number of channels (1 for mono)
 * @param bits          Bits per sample (16)
 * @param periodSize    Period size in frames (160 for 20ms @ 8kHz)
 * @param periodCount   Number of periods (4)
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_open(
        JNIEnv *env, jclass clazz,
        jint card, jint captureDevice, jint playbackDevice,
        jint sampleRate, jint channels, jint bits,
        jint periodSize, jint periodCount) {

    LOGI("Opening audio: card=%d, capture=%d, playback=%d, rate=%d, ch=%d, bits=%d, period=%d/%d",
         card, captureDevice, playbackDevice, sampleRate, channels, bits, periodSize, periodCount);

    if (g_ctx != NULL && g_ctx->is_open) {
        LOGE("Already open, close first");
        return JNI_FALSE;
    }

    /* Allocate context */
    if (g_ctx == NULL) {
        g_ctx = (struct gsm_audio_ctx *)calloc(1, sizeof(struct gsm_audio_ctx));
        if (!g_ctx) {
            LOGE("Failed to allocate context");
            return JNI_FALSE;
        }
        pthread_mutex_init(&g_ctx->lock, NULL);
    }

    g_ctx->card = card;
    g_ctx->capture_device = captureDevice;
    g_ctx->playback_device = playbackDevice;
    g_ctx->sample_rate = sampleRate;
    g_ctx->channels = channels;
    g_ctx->bits = bits;
    g_ctx->period_size = periodSize;
    g_ctx->period_count = periodCount;

    /* PCM config */
    struct pcm_config config;
    memset(&config, 0, sizeof(config));
    config.channels = channels;
    config.rate = sampleRate;
    config.period_size = periodSize;
    config.period_count = periodCount;
    config.format = bits_to_format(bits);
    config.start_threshold = 0;
    config.stop_threshold = 0;
    config.silence_threshold = 0;

    /* Open capture device */
    g_ctx->capture_pcm = pcm_open(card, captureDevice, PCM_IN, &config);
    if (!g_ctx->capture_pcm || !pcm_is_ready(g_ctx->capture_pcm)) {
        LOGE("Failed to open capture PCM %d:%d - %s",
             card, captureDevice,
             g_ctx->capture_pcm ? pcm_get_error(g_ctx->capture_pcm) : "null");
        if (g_ctx->capture_pcm) {
            pcm_close(g_ctx->capture_pcm);
            g_ctx->capture_pcm = NULL;
        }
        return JNI_FALSE;
    }
    LOGI("Capture PCM opened: %d:%d", card, captureDevice);

    /* Open playback device */
    g_ctx->playback_pcm = pcm_open(card, playbackDevice, PCM_OUT, &config);
    if (!g_ctx->playback_pcm || !pcm_is_ready(g_ctx->playback_pcm)) {
        LOGE("Failed to open playback PCM %d:%d - %s",
             card, playbackDevice,
             g_ctx->playback_pcm ? pcm_get_error(g_ctx->playback_pcm) : "null");
        if (g_ctx->playback_pcm) {
            pcm_close(g_ctx->playback_pcm);
            g_ctx->playback_pcm = NULL;
        }
        pcm_close(g_ctx->capture_pcm);
        g_ctx->capture_pcm = NULL;
        return JNI_FALSE;
    }
    LOGI("Playback PCM opened: %d:%d", card, playbackDevice);

    /* Open mixer */
    g_ctx->mixer = mixer_open(card);
    if (!g_ctx->mixer) {
        LOGE("Warning: Failed to open mixer for card %d", card);
        /* Continue anyway - mixer might not be needed */
    } else {
        LOGI("Mixer opened for card %d", card);
    }

    g_ctx->is_open = 1;
    return JNI_TRUE;
}

/*
 * Close audio devices
 */
JNIEXPORT void JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_close(JNIEnv *env, jclass clazz) {
    LOGI("Closing audio");

    if (g_ctx == NULL) return;

    pthread_mutex_lock(&g_ctx->lock);

    if (g_ctx->capture_pcm) {
        pcm_close(g_ctx->capture_pcm);
        g_ctx->capture_pcm = NULL;
    }

    if (g_ctx->playback_pcm) {
        pcm_close(g_ctx->playback_pcm);
        g_ctx->playback_pcm = NULL;
    }

    if (g_ctx->mixer) {
        mixer_close(g_ctx->mixer);
        g_ctx->mixer = NULL;
    }

    g_ctx->is_open = 0;

    pthread_mutex_unlock(&g_ctx->lock);
    LOGI("Audio closed");
}

/*
 * Read audio frame from capture device (GSM -> SIP direction)
 *
 * @param buffer Byte array to fill with PCM data
 * @return Number of bytes read, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_readFrame(
        JNIEnv *env, jclass clazz, jbyteArray buffer) {

    if (g_ctx == NULL || !g_ctx->is_open || !g_ctx->capture_pcm) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        LOGE("Failed to get byte array elements");
        return -1;
    }

    int ret = pcm_read(g_ctx->capture_pcm, buf, len);

    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);

    if (ret != 0) {
        LOGE("pcm_read failed: %s", pcm_get_error(g_ctx->capture_pcm));
        return -1;
    }

    return len;
}

/*
 * Write audio frame to playback device (SIP -> GSM direction)
 *
 * @param buffer Byte array with PCM data
 * @return Number of bytes written, or -1 on error
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_writeFrame(
        JNIEnv *env, jclass clazz, jbyteArray buffer) {

    if (g_ctx == NULL || !g_ctx->is_open || !g_ctx->playback_pcm) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte *buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!buf) {
        LOGE("Failed to get byte array elements");
        return -1;
    }

    int ret = pcm_write(g_ctx->playback_pcm, buf, len);

    (*env)->ReleaseByteArrayElements(env, buffer, buf, JNI_ABORT);

    if (ret != 0) {
        LOGE("pcm_write failed: %s", pcm_get_error(g_ctx->playback_pcm));
        return -1;
    }

    return len;
}

/*
 * Set mixer control value
 *
 * @param card        Sound card number
 * @param controlName Mixer control name (e.g. "MultiMedia1 Mixer VOC_REC_DL")
 * @param value       Value to set (0 or 1 for switches)
 * @return true on success
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_setMixerControl(
        JNIEnv *env, jclass clazz,
        jint card, jstring controlName, jint value) {

    const char *name = (*env)->GetStringUTFChars(env, controlName, NULL);
    if (!name) {
        LOGE("Failed to get control name string");
        return JNI_FALSE;
    }

    LOGD("setMixerControl: card=%d, control='%s', value=%d", card, name, value);

    struct mixer *mix = mixer_open(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    int ret = mixer_ctl_set_value(ctl, 0, value);
    if (ret < 0) {
        LOGE("Failed to set mixer control '%s' to %d: %d", name, value, ret);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        return JNI_FALSE;
    }

    LOGI("Set mixer control '%s' = %d", name, value);

    mixer_close(mix);
    (*env)->ReleaseStringUTFChars(env, controlName, name);
    return JNI_TRUE;
}

/*
 * Set mixer control ENUM value by string
 *
 * @param card        Sound card number
 * @param controlName Mixer control name (e.g. "DEC1 MUX")
 * @param value       String value to set (e.g. "ZERO", "ADC1", "ADC2")
 * @return true on success
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_setMixerControlEnum(
        JNIEnv *env, jclass clazz,
        jint card, jstring controlName, jstring value) {

    const char *name = (*env)->GetStringUTFChars(env, controlName, NULL);
    const char *val = (*env)->GetStringUTFChars(env, value, NULL);
    if (!name || !val) {
        LOGE("Failed to get control name or value string");
        if (name) (*env)->ReleaseStringUTFChars(env, controlName, name);
        if (val) (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    LOGD("setMixerControlEnum: card=%d, control='%s', value='%s'", card, name, val);

    struct mixer *mix = mixer_open(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mix, name);
    if (!ctl) {
        LOGE("Mixer control '%s' not found", name);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    int ret = mixer_ctl_set_enum_by_string(ctl, val);
    if (ret < 0) {
        LOGE("Failed to set mixer control '%s' to '%s': %d", name, val, ret);
        mixer_close(mix);
        (*env)->ReleaseStringUTFChars(env, controlName, name);
        (*env)->ReleaseStringUTFChars(env, value, val);
        return JNI_FALSE;
    }

    LOGI("Set mixer control '%s' = '%s'", name, val);

    mixer_close(mix);
    (*env)->ReleaseStringUTFChars(env, controlName, name);
    (*env)->ReleaseStringUTFChars(env, value, val);
    return JNI_TRUE;
}

/*
 * Get list of mixer controls (for device discovery)
 *
 * @param card Sound card number
 * @return String array of control names, or null on error
 */
JNIEXPORT jobjectArray JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getMixerControls(
        JNIEnv *env, jclass clazz, jint card) {

    struct mixer *mix = mixer_open(card);
    if (!mix) {
        LOGE("Failed to open mixer for card %d", card);
        return NULL;
    }

    unsigned int count = mixer_get_num_ctls(mix);
    LOGD("Card %d has %u mixer controls", card, count);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    for (unsigned int i = 0; i < count; i++) {
        struct mixer_ctl *ctl = mixer_get_ctl(mix, i);
        if (ctl) {
            const char *name = mixer_ctl_get_name(ctl);
            jstring jname = (*env)->NewStringUTF(env, name ? name : "");
            (*env)->SetObjectArrayElement(env, result, i, jname);
            (*env)->DeleteLocalRef(env, jname);
        }
    }

    mixer_close(mix);
    return result;
}

/*
 * Check if audio is open
 */
JNIEXPORT jboolean JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_isOpen(JNIEnv *env, jclass clazz) {
    return (g_ctx != NULL && g_ctx->is_open) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Get frame size in bytes
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getFrameSize(JNIEnv *env, jclass clazz) {
    if (g_ctx == NULL || !g_ctx->is_open) {
        return 0;
    }
    /* period_size samples * channels * bytes_per_sample */
    return g_ctx->period_size * g_ctx->channels * (g_ctx->bits / 8);
}

/*
 * Get list of PCM devices for a card
 * Returns array of strings: "device_num: name (capture/playback)"
 *
 * @param card Sound card number
 * @param isCapture true for capture devices, false for playback
 * @return String array of device descriptions
 */
JNIEXPORT jobjectArray JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getPcmDevices(
        JNIEnv *env, jclass clazz, jint card, jboolean isCapture) {

    /* Read /proc/asound/pcm to get device info */
    FILE *fp = fopen("/proc/asound/pcm", "r");
    if (!fp) {
        LOGE("Failed to open /proc/asound/pcm");
        return NULL;
    }

    /* First pass: count matching devices */
    char line[256];
    int count = 0;
    char cardStr[8];
    snprintf(cardStr, sizeof(cardStr), "%02d-", card);

    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, cardStr, 3) == 0) {
            /* Check if it's capture or playback */
            int hasCapture = (strstr(line, "capture") != NULL);
            int hasPlayback = (strstr(line, "playback") != NULL);
            if ((isCapture && hasCapture) || (!isCapture && hasPlayback)) {
                count++;
            }
        }
    }

    LOGD("Found %d %s devices on card %d", count, isCapture ? "capture" : "playback", card);

    /* Create result array */
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    /* Second pass: fill array */
    rewind(fp);
    int idx = 0;
    while (fgets(line, sizeof(line), fp) && idx < count) {
        if (strncmp(line, cardStr, 3) == 0) {
            int hasCapture = (strstr(line, "capture") != NULL);
            int hasPlayback = (strstr(line, "playback") != NULL);
            if ((isCapture && hasCapture) || (!isCapture && hasPlayback)) {
                /* Parse: "00-36: msm-pcm-voice-v2 (*) : : playback 1 : capture 1" */
                int devNum = 0;
                char devName[128] = "";

                /* Get device number after dash */
                char *dash = strchr(line, '-');
                if (dash) {
                    devNum = atoi(dash + 1);
                }

                /* Get device name (between ": " and next " :") */
                char *nameStart = strchr(line, ':');
                if (nameStart) {
                    nameStart += 2; /* skip ": " */
                    char *nameEnd = strstr(nameStart, " :");
                    if (nameEnd) {
                        int len = nameEnd - nameStart;
                        if (len > 0 && len < sizeof(devName)) {
                            strncpy(devName, nameStart, len);
                            devName[len] = '\0';
                        }
                    }
                }

                /* Format: "36: msm-pcm-voice-v2" */
                char formatted[160];
                snprintf(formatted, sizeof(formatted), "%d: %s", devNum, devName);

                jstring jstr = (*env)->NewStringUTF(env, formatted);
                (*env)->SetObjectArrayElement(env, result, idx, jstr);
                (*env)->DeleteLocalRef(env, jstr);
                idx++;
            }
        }
    }

    fclose(fp);
    return result;
}

/*
 * Get number of sound cards
 */
JNIEXPORT jint JNICALL
Java_org_onetwoone_gateway_GsmAudioNative_getCardCount(JNIEnv *env, jclass clazz) {
    /* Check /proc/asound/cards */
    FILE *fp = fopen("/proc/asound/cards", "r");
    if (!fp) {
        return 0;
    }

    int maxCard = -1;
    char line[256];
    while (fgets(line, sizeof(line), fp)) {
        int cardNum;
        if (sscanf(line, " %d [", &cardNum) == 1) {
            if (cardNum > maxCard) maxCard = cardNum;
        }
    }

    fclose(fp);
    return maxCard + 1;
}
