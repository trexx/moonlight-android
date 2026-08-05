// Native AAudio output path for Moonlight.
//
// Some Android TV devices (Google TV Streamer and friends) refuse AudioTrack's fast/low-latency
// output even when it is explicitly requested, producing roughly half a second to a second of
// audio delay while video and input latency stay low. Going straight to AAudio with a data
// callback avoids that path entirely.
//
// The data callback runs on a realtime audio thread. Nothing in it may block, allocate, log or
// take a lock, so the ring buffer below is a strict single-producer/single-consumer lock-free
// queue: playDecodedAudio() (the decode thread) only ever advances writeIndex, and the callback
// only ever advances readIndex.

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "MoonlightAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    AAudioStream* stream;

    int32_t channelCount;
    int32_t sampleRate;
    int32_t channelMask;

    int16_t* ring;
    uint32_t ringCapacity; // In samples. Always a power of two.
    uint32_t ringMask;

    // Free-running sample counters. Unsigned wraparound makes (write - read) correct even when
    // the counters themselves overflow, so they never need to be clamped.
    _Atomic uint32_t readIndex;
    _Atomic uint32_t writeIndex;

    _Atomic bool dead;
    _Atomic bool recovering;
    _Atomic uint32_t droppedBuffers;
} AAudioRenderer;

// AAudioStreamBuilder_setChannelMask() arrived in API 32, above our minSdk of 30, so the NDK
// headers mark it unavailable and refuse to let us call it directly. Resolving it by name keeps
// the opt-in to weak symbol linking scoped to this one function rather than applying
// __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__ to every symbol in the module.
typedef void (*AAudioStreamBuilder_setChannelMask_fn)(AAudioStreamBuilder* builder,
                                                      aaudio_channel_mask_t channelMask);

static AAudioStreamBuilder_setChannelMask_fn resolveSetChannelMask(void) {
    static AAudioStreamBuilder_setChannelMask_fn cached;
    static bool resolved;

    if (!resolved) {
        cached = (AAudioStreamBuilder_setChannelMask_fn)
                dlsym(RTLD_DEFAULT, "AAudioStreamBuilder_setChannelMask");
        resolved = true;
    }

    return cached;
}

static const char* resultText(aaudio_result_t result) {
    const char* text = AAudio_convertResultToText(result);
    return text != NULL ? text : "unknown";
}

static uint32_t roundUpToPowerOfTwo(uint32_t value) {
    uint32_t result = 1;
    while (result < value) {
        result <<= 1;
    }
    return result;
}

static aaudio_data_callback_result_t dataCallback(AAudioStream* stream, void* userData,
                                                  void* audioData, int32_t numFrames) {
    (void)stream;

    AAudioRenderer* ctx = (AAudioRenderer*)userData;
    int16_t* out = (int16_t*)audioData;
    uint32_t requested = (uint32_t)numFrames * (uint32_t)ctx->channelCount;

    // Acquire on writeIndex pairs with the release in nativeEnqueue(), so everything the producer
    // wrote into the ring before publishing is visible to us here.
    uint32_t read = atomic_load_explicit(&ctx->readIndex, memory_order_relaxed);
    uint32_t write = atomic_load_explicit(&ctx->writeIndex, memory_order_acquire);

    uint32_t available = write - read;
    uint32_t toCopy = available < requested ? available : requested;

    uint32_t copied = 0;
    while (copied < toCopy) {
        uint32_t offset = (read + copied) & ctx->ringMask;
        uint32_t chunk = ctx->ringCapacity - offset;
        if (chunk > toCopy - copied) {
            chunk = toCopy - copied;
        }
        memcpy(out + copied, ctx->ring + offset, (size_t)chunk * sizeof(int16_t));
        copied += chunk;
    }

    atomic_store_explicit(&ctx->readIndex, read + copied, memory_order_release);

    // Underrun. Emit silence for the remainder rather than glitching or stalling the stream.
    if (copied < requested) {
        memset(out + copied, 0, (size_t)(requested - copied) * sizeof(int16_t));
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static aaudio_result_t openStream(AAudioRenderer* ctx, aaudio_sharing_mode_t sharingMode);

// Runs on a detached thread because AAudio forbids closing or stopping a stream from inside its
// own error callback. A route change (HDMI replug, switching to a soundbar, Bluetooth connect)
// disconnects the stream, and without this the audio would stop permanently.
static void* recoverThread(void* userData) {
    AAudioRenderer* ctx = (AAudioRenderer*)userData;

    AAudioStream* oldStream = ctx->stream;
    if (oldStream != NULL) {
        AAudioStream_requestStop(oldStream);
        AAudioStream_close(oldStream);
        ctx->stream = NULL;
    }

    // The ring is deliberately left alone. The producer keeps running during recovery, so
    // resetting the indices from here would mean two threads writing them. Whatever is queued is
    // at most one ring-length of audio (tens of milliseconds) and the new stream just drains it.
    aaudio_result_t result = openStream(ctx, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (result != AAUDIO_OK) {
        result = openStream(ctx, AAUDIO_SHARING_MODE_SHARED);
    }

    if (result == AAUDIO_OK) {
        result = AAudioStream_requestStart(ctx->stream);
    }

    if (result != AAUDIO_OK) {
        LOGE("Unable to recover AAudio stream after disconnect: %s", resultText(result));
        if (ctx->stream != NULL) {
            AAudioStream_close(ctx->stream);
            ctx->stream = NULL;
        }
        // The Java wrapper polls this and falls back to AudioTrack.
        atomic_store_explicit(&ctx->dead, true, memory_order_release);
    }
    else {
        LOGI("Recovered AAudio stream after device disconnect");
    }

    atomic_store_explicit(&ctx->recovering, false, memory_order_release);
    return NULL;
}

static void errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error) {
    (void)stream;

    AAudioRenderer* ctx = (AAudioRenderer*)userData;

    if (error != AAUDIO_ERROR_DISCONNECTED) {
        LOGE("AAudio stream error: %s", resultText(error));
        atomic_store_explicit(&ctx->dead, true, memory_order_release);
        return;
    }

    bool expected = false;
    if (!atomic_compare_exchange_strong_explicit(&ctx->recovering, &expected, true,
                                                 memory_order_acq_rel, memory_order_relaxed)) {
        // A recovery is already in flight
        return;
    }

    pthread_t thread;
    if (pthread_create(&thread, NULL, recoverThread, ctx) != 0) {
        LOGE("Unable to spawn AAudio recovery thread");
        atomic_store_explicit(&ctx->dead, true, memory_order_release);
        atomic_store_explicit(&ctx->recovering, false, memory_order_release);
        return;
    }

    pthread_detach(thread);
}

static aaudio_result_t openStream(AAudioRenderer* ctx, aaudio_sharing_mode_t sharingMode) {
    AAudioStreamBuilder* builder = NULL;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("AAudio_createStreamBuilder failed: %s", resultText(result));
        return result;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, ctx->sampleRate);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, sharingMode);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);

    // MOVIE, not SONIFICATION. Sonification is for UI beeps and notifications, and on some
    // devices it routes differently and follows a different volume stream.
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MOVIE);

    // A channel *count* alone leaves the speaker layout undefined for surround, which is how
    // centre/LFE/rears end up silent. moonlight-common-c already hands us a WAVE-style channel
    // mask whose bit layout is identical to AAudio's, so pass it straight through. The setter is
    // only available from API 32; the Java side keeps us on stereo below that, where the count
    // is unambiguous.
    AAudioStreamBuilder_setChannelMask_fn setChannelMask = resolveSetChannelMask();
    if (setChannelMask != NULL) {
        setChannelMask(builder, (aaudio_channel_mask_t)ctx->channelMask);
    }
    else {
        AAudioStreamBuilder_setChannelCount(builder, ctx->channelCount);
    }

    AAudioStreamBuilder_setDataCallback(builder, dataCallback, ctx);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, ctx);

    AAudioStream* stream = NULL;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGW("AAudio open failed for sharing mode %d: %s", sharingMode, resultText(result));
        return result;
    }

    ctx->stream = stream;
    return AAUDIO_OK;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeSetup(
        JNIEnv* env, jclass clazz, jint channelCount, jint channelMask, jint sampleRate,
        jint samplesPerFrame) {
    (void)env;
    (void)clazz;

    AAudioRenderer* ctx = (AAudioRenderer*)calloc(1, sizeof(AAudioRenderer));
    if (ctx == NULL) {
        return 0;
    }

    ctx->channelCount = channelCount;
    ctx->channelMask = channelMask;
    ctx->sampleRate = sampleRate;

    aaudio_result_t result = openStream(ctx, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (result != AAUDIO_OK) {
        result = openStream(ctx, AAUDIO_SHARING_MODE_SHARED);
        if (result != AAUDIO_OK) {
            free(ctx);
            return 0;
        }
    }

    int32_t burstFrames = AAudioStream_getFramesPerBurst(ctx->stream);

    // Two bursts is the usual low-latency target for a callback-driven stream.
    int32_t targetBufferFrames = burstFrames * 2;
    if (targetBufferFrames < samplesPerFrame * 2) {
        targetBufferFrames = samplesPerFrame * 2;
    }
    AAudioStream_setBufferSizeInFrames(ctx->stream, targetBufferFrames);

    // The ring only needs to cover jitter between the decode thread and the callback. Keeping it
    // small is what bounds added latency, since a full ring makes the producer drop.
    uint32_t ringFrames = (uint32_t)(burstFrames * 4);
    if (ringFrames < (uint32_t)(samplesPerFrame * 6)) {
        ringFrames = (uint32_t)(samplesPerFrame * 6);
    }

    ctx->ringCapacity = roundUpToPowerOfTwo(ringFrames * (uint32_t)channelCount);
    ctx->ringMask = ctx->ringCapacity - 1;
    ctx->ring = (int16_t*)calloc(ctx->ringCapacity, sizeof(int16_t));
    if (ctx->ring == NULL) {
        LOGE("Failed to allocate AAudio ring buffer");
        AAudioStream_close(ctx->stream);
        free(ctx);
        return 0;
    }

    result = AAudioStream_requestStart(ctx->stream);
    if (result != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed: %s", resultText(result));
        AAudioStream_close(ctx->stream);
        free(ctx->ring);
        free(ctx);
        return 0;
    }

    LOGI("AAudio stream started: %d ch (mask 0x%x), %d Hz, %d frame burst, %d frame buffer, "
         "%s mode, ring %u samples",
         channelCount, channelMask, sampleRate, burstFrames,
         AAudioStream_getBufferSizeInFrames(ctx->stream),
         AAudioStream_getSharingMode(ctx->stream) == AAUDIO_SHARING_MODE_EXCLUSIVE
                 ? "exclusive" : "shared",
         ctx->ringCapacity);

    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeEnqueue(
        JNIEnv* env, jclass clazz, jlong handle, jshortArray data, jint length) {
    (void)clazz;

    AAudioRenderer* ctx = (AAudioRenderer*)(intptr_t)handle;
    if (ctx == NULL || atomic_load_explicit(&ctx->dead, memory_order_acquire)) {
        return;
    }

    uint32_t write = atomic_load_explicit(&ctx->writeIndex, memory_order_relaxed);
    uint32_t read = atomic_load_explicit(&ctx->readIndex, memory_order_acquire);
    uint32_t freeSamples = ctx->ringCapacity - (write - read);

    // Only the consumer may advance readIndex, so we can't discard the oldest data here without
    // breaking the single-writer invariant. Dropping the incoming buffer instead is sufficient:
    // the ring's capacity is what bounds latency, and it can never grow past that.
    if ((uint32_t)length > freeSamples) {
        atomic_fetch_add_explicit(&ctx->droppedBuffers, 1, memory_order_relaxed);
        return;
    }

    uint32_t offset = write & ctx->ringMask;
    uint32_t firstChunk = ctx->ringCapacity - offset;
    if (firstChunk > (uint32_t)length) {
        firstChunk = (uint32_t)length;
    }

    (*env)->GetShortArrayRegion(env, data, 0, (jsize)firstChunk, ctx->ring + offset);
    if (firstChunk < (uint32_t)length) {
        (*env)->GetShortArrayRegion(env, data, (jsize)firstChunk,
                                    (jsize)((uint32_t)length - firstChunk), ctx->ring);
    }

    // Release pairs with the acquire in dataCallback()
    atomic_store_explicit(&ctx->writeIndex, write + (uint32_t)length, memory_order_release);
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeIsDead(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;

    AAudioRenderer* ctx = (AAudioRenderer*)(intptr_t)handle;
    if (ctx == NULL) {
        return JNI_TRUE;
    }

    return atomic_load_explicit(&ctx->dead, memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeCleanup(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;

    AAudioRenderer* ctx = (AAudioRenderer*)(intptr_t)handle;
    if (ctx == NULL) {
        return;
    }

    // Stop the callback before anything it touches goes away.
    if (ctx->stream != NULL) {
        AAudioStream_requestStop(ctx->stream);
        AAudioStream_close(ctx->stream);
        ctx->stream = NULL;
    }

    uint32_t dropped = atomic_load_explicit(&ctx->droppedBuffers, memory_order_relaxed);
    if (dropped != 0) {
        LOGW("Dropped %u audio buffers due to ring buffer overrun", dropped);
    }

    free(ctx->ring);
    free(ctx);
}
