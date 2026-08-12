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
#include <time.h>

#define LOG_TAG "MoonlightAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    AAudioStream* stream;

    int32_t channelCount;
    int32_t sampleRate;
    int32_t channelMask;

    // Retained so the buffer can be resized again when recoverThread() reopens the stream; it
    // arrives as a nativeSetup() argument, which is why the recovery path could not size the
    // replacement before. Deliberately placed here: channelMask left four bytes of tail padding
    // ahead of the 8-byte-aligned ring pointer, so this fills the hole rather than shifting any
    // field the data callback touches.
    int32_t samplesPerFrame;

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

    // What AAudio actually granted, cached when the stream opens. Requesting LOW_LATENCY is not
    // the same as getting it, and a stream that quietly landed on PERFORMANCE_MODE_NONE is the
    // half-second delay this file exists to avoid - from the outside it is indistinguishable from
    // a working one. Atomic because recoverThread rewrites them while another thread may read.
    _Atomic int32_t actualPerformanceMode;
    _Atomic int32_t actualSharingMode;
    _Atomic int32_t actualSampleRate;
    _Atomic int32_t bufferSizeFrames;

    // Session totals, folded in whenever a stream is retired - replaced by recovery, or closed at
    // teardown. Written only from cold paths, never from the data callback.
    _Atomic uint32_t recoveryCount;
    _Atomic uint32_t xrunCount;
    _Atomic uint32_t silenceSamplesTotal;
    _Atomic uint64_t framesWrittenTotal;

    // readIndex when the current stream opened, so a stream's own contribution can be separated
    // out. The ring deliberately survives recovery, so readIndex does not restart with the stream.
    _Atomic uint32_t readIndexAtStreamStart;

#ifdef LC_DEBUG
    // Debug builds only, and inside the guard so release keeps this struct's exact layout. The
    // derived silence figure covers release; what these add is the *callback* count, which is
    // what separates one long dropout from constant micro-starvation. They are gated because
    // they are the only instrumentation here that would run on the realtime callback thread.
    _Atomic uint32_t underrunSamples;
    _Atomic uint32_t underrunCallbacks;

    // End-to-end output latency, sampled once a second from the audio decode thread. This is the
    // figure that makes this path comparable with AudioTrack's, which is granted the same
    // performance mode and the same buffer size on the supported hardware - so the difference
    // between them, if any, is in what each queues in front of the sink rather than in the sink.
    //
    // The lock exists because sampling is the only thing here that reads ctx->stream from a thread
    // that does not own it: the data callback must never call getTimestamp(), and nativeGetStats()
    // deliberately never touches the stream at all, which leaves the decode thread - and
    // recoverThread can close the stream underneath it. Sampling uses trylock and skips on
    // contention, so a recovery in progress costs a dropped sample rather than a stalled decode
    // thread. All of it sits inside this guard, so release keeps its lock-free hot path, its struct
    // layout and its byte-identical callback.
    pthread_mutex_t streamLock;
    uint32_t latencySampleCountdown;
    uint32_t latencySampleInterval;
    uint32_t latencySamples;
    uint64_t latencyTotalUs;
    uint32_t latencyMinUs;
    uint32_t latencyMaxUs;
#endif
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

// Naming what AAudio actually granted, which is not necessarily what was asked for. Unlike
// AAudioStreamBuilder_setChannelMask() above, the getters these describe all arrived in API 26,
// below our minSdk of 30, so they are called directly and need none of the dlsym apparatus.
static const char* performanceModeText(aaudio_performance_mode_t mode) {
    switch (mode) {
        case AAUDIO_PERFORMANCE_MODE_LOW_LATENCY:  return "low latency";
        case AAUDIO_PERFORMANCE_MODE_POWER_SAVING: return "power saving";
        case AAUDIO_PERFORMANCE_MODE_NONE:         return "none";
        default:                                   return "unknown";
    }
}

static const char* sharingModeText(aaudio_sharing_mode_t mode) {
    switch (mode) {
        case AAUDIO_SHARING_MODE_EXCLUSIVE: return "exclusive";
        case AAUDIO_SHARING_MODE_SHARED:    return "shared";
        default:                            return "unknown";
    }
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

#ifdef LC_DEBUG
        // The only instrumentation in this function, and it is compiled out of release builds
        // entirely rather than merely made cheap.
        //
        // Load-add-store rather than atomic_fetch_add, for the same reason readIndex above is
        // written that way: this callback is the only writer, so no read-modify-write is needed.
        // That is not a micro-optimisation. A relaxed fetch_add compiles to a call to
        // __aarch64_ldadd4_relax under the NDK's default -moutline-atomics on arm64, and to an
        // ldrex/strex pair on armeabi-v7a - and a function call has no business in a realtime
        // callback even in a debug build. A plain load and store lowers to ldr/str on both.
        atomic_store_explicit(&ctx->underrunSamples,
                              atomic_load_explicit(&ctx->underrunSamples, memory_order_relaxed) +
                                      (requested - copied),
                              memory_order_relaxed);
        atomic_store_explicit(&ctx->underrunCallbacks,
                              atomic_load_explicit(&ctx->underrunCallbacks, memory_order_relaxed) + 1,
                              memory_order_relaxed);
#endif
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static aaudio_result_t openStream(AAudioRenderer* ctx, aaudio_sharing_mode_t sharingMode);

#ifdef LC_DEBUG
// Samples how long it will be before the audio just enqueued is actually heard.
//
// Called from the audio decode thread, throttled to once a second by nativeEnqueue(). Uses
// trylock deliberately: a recovery holds the lock for as long as it takes to close a stream, and
// blocking the decode thread on that would interrupt audio to measure it. A skipped sample costs
// nothing - there are sixty more in the next minute.
static void sampleOutputLatency(AAudioRenderer* ctx) {
    if (pthread_mutex_trylock(&ctx->streamLock) != 0) {
        return;
    }

    AAudioStream* stream = ctx->stream;
    if (stream == NULL) {
        pthread_mutex_unlock(&ctx->streamLock);
        return;
    }

    int64_t framePosition;
    int64_t timeNanos;
    aaudio_result_t result =
            AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &framePosition, &timeNanos);
    int64_t framesWritten = AAudioStream_getFramesWritten(stream);
    int32_t rate = atomic_load_explicit(&ctx->actualSampleRate, memory_order_relaxed);

    pthread_mutex_unlock(&ctx->streamLock);

    // getTimestamp() reports no service until the stream has actually presented something, and
    // returns an error rather than stale data on a disconnected stream. Either way there is
    // nothing to record - and nothing wrong.
    if (result != AAUDIO_OK || rate <= 0) {
        return;
    }

    // framePosition was presented at timeNanos, which is already in the past by the time we read
    // it, so project the DAC forward to now. Whatever is still ahead of it is the delay the next
    // frame enqueued will incur.
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    int64_t nowNanos = (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;

    int64_t presentedNow = framePosition + ((nowNanos - timeNanos) * rate / 1000000000LL);
    int64_t queuedFrames = framesWritten - presentedNow;

    // A negative result means the projection overran what the callback has produced, so the stream
    // had drained and the timestamp is stale. Discarded rather than clamped: averaging in a zero
    // would drag the figure down and make this path look better than it is.
    if (queuedFrames < 0) {
        return;
    }

    uint32_t latencyUs = (uint32_t)(queuedFrames * 1000000LL / rate);

    // Plain arithmetic, not atomics: the decode thread is the only writer, and nativeCleanup only
    // reads these after moonlight-common-c has joined that thread.
    ctx->latencyTotalUs += latencyUs;
    ctx->latencySamples++;

    if (ctx->latencySamples == 1 || latencyUs < ctx->latencyMinUs) {
        ctx->latencyMinUs = latencyUs;
    }
    if (latencyUs > ctx->latencyMaxUs) {
        ctx->latencyMaxUs = latencyUs;
    }
}
#endif

// Folds a stream's contribution into the session totals, then stops and closes it.
//
// Both callers own the stream at this point, which is what makes the getters safe to call - and
// they have to be called before the close, since they stop working afterwards. Keeping the
// sampling and the close together in one function is what stops the two call sites from drifting.
static void retireStream(AAudioRenderer* ctx) {
    AAudioStream* stream = ctx->stream;
    if (stream == NULL) {
        return;
    }

#ifdef LC_DEBUG
    // Held across the close so the latency sampler on the decode thread cannot be inside
    // getTimestamp() on a stream that is going away. Nothing else contends for it.
    pthread_mutex_lock(&ctx->streamLock);
#endif

    // A negative return is an aaudio_result_t error code rather than a count, which is what a
    // disconnected stream gives us here.
    int32_t xruns = AAudioStream_getXRunCount(stream);
    if (xruns > 0) {
        atomic_fetch_add_explicit(&ctx->xrunCount, (uint32_t)xruns, memory_order_relaxed);
    }

    int64_t framesWritten = AAudioStream_getFramesWritten(stream);
    if (framesWritten > 0) {
        atomic_fetch_add_explicit(&ctx->framesWrittenTotal, (uint64_t)framesWritten,
                                  memory_order_relaxed);

        // How much silence the callback emitted, derived rather than counted. getFramesWritten()
        // covers every frame the callback produced; readIndex advances only by what it actually
        // took from the ring; the difference is what got papered over. That keeps the underrun
        // figure available in release builds without the callback counting anything itself.
        //
        // Deliberately uint32 throughout: the subtraction is then correct across readIndex's own
        // wraparound, which is the same ~12.4 hours a uint32 sample counter would give anyway.
        uint32_t output = (uint32_t)((uint64_t)framesWritten * (uint32_t)ctx->channelCount);
        uint32_t fromRing = atomic_load_explicit(&ctx->readIndex, memory_order_relaxed) -
                            atomic_load_explicit(&ctx->readIndexAtStreamStart, memory_order_relaxed);
        atomic_fetch_add_explicit(&ctx->silenceSamplesTotal, output - fromRing,
                                  memory_order_relaxed);
    }

    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    ctx->stream = NULL;

#ifdef LC_DEBUG
    pthread_mutex_unlock(&ctx->streamLock);
#endif
}

// Runs on a detached thread because AAudio forbids closing or stopping a stream from inside its
// own error callback. A route change (HDMI replug, switching to a soundbar, Bluetooth connect)
// disconnects the stream, and without this the audio would stop permanently.
static void* recoverThread(void* userData) {
    AAudioRenderer* ctx = (AAudioRenderer*)userData;

    retireStream(ctx);

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
        // Naming the granted mode matters here: recovery can quietly land on a degraded stream,
        // which would otherwise be indistinguishable from a clean one. The buffer size is named
        // for the same reason, and because its absence is what hid the replacement stream running
        // on AAudio's default buffer - a 4x latency regression that no log line reported.
        LOGI("Recovered AAudio stream after device disconnect: %s / %s, %d frame buffer",
             performanceModeText(
                     atomic_load_explicit(&ctx->actualPerformanceMode, memory_order_relaxed)),
             sharingModeText(atomic_load_explicit(&ctx->actualSharingMode, memory_order_relaxed)),
             atomic_load_explicit(&ctx->bufferSizeFrames, memory_order_relaxed));
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

    // A stream that quietly rebuilds itself over and over is a real defect, and nothing counted
    // it before. Not the realtime callback: this runs on AAudio's error callback thread.
    atomic_fetch_add_explicit(&ctx->recoveryCount, 1, memory_order_relaxed);

    pthread_t thread;
    if (pthread_create(&thread, NULL, recoverThread, ctx) != 0) {
        LOGE("Unable to spawn AAudio recovery thread");
        atomic_store_explicit(&ctx->dead, true, memory_order_release);
        atomic_store_explicit(&ctx->recovering, false, memory_order_release);
        return;
    }

    pthread_detach(thread);
}

// Sizes a freshly opened stream's buffer down to the low-latency target, and caches what was
// granted.
//
// Called from openStream() so that every stream gets it, not just the first. recoverThread()
// reopens the stream, and a replacement starts at AAudio's default buffer size - on the Shield TV
// that is 2048 frames against a 256-frame burst, so 42.7 ms of queued audio where the target is
// 10.7 ms. The HDMI renegotiation that Moonlight's own display mode switch triggers disconnects
// the stream twice within two seconds of every stream start on that box, so the default is what
// every session actually ran on, quietly undoing the latency win this file exists for.
static void applyLowLatencyBufferSize(AAudioRenderer* ctx, AAudioStream* stream) {
    // Two bursts is the usual low-latency target for a callback-driven stream.
    int32_t targetBufferFrames = AAudioStream_getFramesPerBurst(stream) * 2;
    if (targetBufferFrames < ctx->samplesPerFrame * 2) {
        targetBufferFrames = ctx->samplesPerFrame * 2;
    }

    AAudioStream_setBufferSizeInFrames(stream, targetBufferFrames);

    // Read back rather than assuming the request was taken - AAudio clamps it to the stream's
    // capacity - and cache it so nativeGetStats() never has to touch the stream.
    atomic_store_explicit(&ctx->bufferSizeFrames, AAudioStream_getBufferSizeInFrames(stream),
                          memory_order_relaxed);
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

    // Read back what we were actually given. This lives here rather than in nativeSetup() because
    // recoverThread re-enters openStream(): a route change can land us on a degraded replacement
    // stream, and that is otherwise completely invisible.
    aaudio_performance_mode_t performanceMode = AAudioStream_getPerformanceMode(stream);

    atomic_store_explicit(&ctx->actualPerformanceMode, (int32_t)performanceMode,
                          memory_order_relaxed);
    atomic_store_explicit(&ctx->actualSharingMode, (int32_t)AAudioStream_getSharingMode(stream),
                          memory_order_relaxed);
    atomic_store_explicit(&ctx->actualSampleRate, AAudioStream_getSampleRate(stream),
                          memory_order_relaxed);

    // Bring the buffer down to the low-latency target before anything starts playing through it.
    applyLowLatencyBufferSize(ctx, stream);

    // Baseline for the derived silence figure in retireStream(). Safe to take here because no
    // callback can run until requestStart(), so readIndex cannot move between now and then.
    atomic_store_explicit(&ctx->readIndexAtStreamStart,
                          atomic_load_explicit(&ctx->readIndex, memory_order_relaxed),
                          memory_order_relaxed);

    // Not fatal, and deliberately not a reason to fall back. AudioTrack is the path this file
    // exists to escape, so dropping to it would trade a reported problem for a silent one - and
    // we already knowingly accept the SHARED downgrade below. Warn and carry on.
    if (performanceMode != AAUDIO_PERFORMANCE_MODE_LOW_LATENCY) {
        LOGW("AAudio granted performance mode '%s' after LOW_LATENCY was requested. Audio latency "
             "will be no better than AudioTrack's.", performanceModeText(performanceMode));
    }

    // Published under the lock for the same reason retireStream() clears it under the lock: the
    // latency sampler reads this pointer from the decode thread, and recovery reaches here having
    // just set it to NULL.
#ifdef LC_DEBUG
    pthread_mutex_lock(&ctx->streamLock);
#endif

    ctx->stream = stream;

#ifdef LC_DEBUG
    pthread_mutex_unlock(&ctx->streamLock);
#endif

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

    // Must be set before openStream(), which sizes the buffer against it.
    ctx->samplesPerFrame = samplesPerFrame;

#ifdef LC_DEBUG
    // Before openStream(), which publishes ctx->stream under this lock.
    pthread_mutex_init(&ctx->streamLock, NULL);

    // One sample a second. Buffers arrive at sampleRate/samplesPerFrame per second, so derive it
    // rather than hardcoding 200 - a surround configuration changes the frame size.
    ctx->latencySampleInterval = samplesPerFrame > 0 ? (uint32_t)(sampleRate / samplesPerFrame) : 1;
    if (ctx->latencySampleInterval == 0) {
        ctx->latencySampleInterval = 1;
    }
    ctx->latencySampleCountdown = ctx->latencySampleInterval;
#endif

    aaudio_result_t result = openStream(ctx, AAUDIO_SHARING_MODE_EXCLUSIVE);
    if (result != AAUDIO_OK) {
        result = openStream(ctx, AAUDIO_SHARING_MODE_SHARED);
        if (result != AAUDIO_OK) {
#ifdef LC_DEBUG
            pthread_mutex_destroy(&ctx->streamLock);
#endif
            free(ctx);
            return 0;
        }
    }

    // openStream() has already sized the stream buffer; this is only needed for the ring below.
    int32_t burstFrames = AAudioStream_getFramesPerBurst(ctx->stream);

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
#ifdef LC_DEBUG
        pthread_mutex_destroy(&ctx->streamLock);
#endif
        free(ctx);
        return 0;
    }

    result = AAudioStream_requestStart(ctx->stream);
    if (result != AAUDIO_OK) {
        LOGE("AAudioStream_requestStart failed: %s", resultText(result));
        AAudioStream_close(ctx->stream);
#ifdef LC_DEBUG
        pthread_mutex_destroy(&ctx->streamLock);
#endif
        free(ctx->ring);
        free(ctx);
        return 0;
    }

    // Everything here is read back from the stream rather than echoed from the arguments. The
    // sample rate is included because a silently resampled stream is a real, latency-adding
    // downgrade that would otherwise look like a clean start.
    int32_t grantedSampleRate = atomic_load_explicit(&ctx->actualSampleRate, memory_order_relaxed);

    LOGI("AAudio stream started: %d ch (mask 0x%x), %d Hz (requested %d), %d frame burst, "
         "%d frame buffer, %s / %s, ring %u samples",
         channelCount, channelMask, grantedSampleRate, sampleRate, burstFrames,
         AAudioStream_getBufferSizeInFrames(ctx->stream),
         performanceModeText(
                 atomic_load_explicit(&ctx->actualPerformanceMode, memory_order_relaxed)),
         sharingModeText(atomic_load_explicit(&ctx->actualSharingMode, memory_order_relaxed)),
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

#ifdef LC_DEBUG
    // After the publish, so the measurement never delays the audio it is measuring. Throttled to
    // once a second because getTimestamp() is a real call into AAudio, not a field read.
    if (--ctx->latencySampleCountdown == 0) {
        ctx->latencySampleCountdown = ctx->latencySampleInterval;
        sampleOutputLatency(ctx);
    }
#endif
}

// Mirrors of the normalised constants on MoonBridge. AAudio numbers its own performance modes
// from 10, and AudioTrack numbers its from 0, so the raw platform value would mean nothing to a
// caller that does not already know which backend produced it.
#define STAT_NA                     (-1)
#define STAT_BACKEND_AAUDIO         1
#define STAT_PERF_MODE_NONE         0
#define STAT_PERF_MODE_LOW_LATENCY  1
#define STAT_PERF_MODE_POWER_SAVING 2
#define STAT_SHARING_MODE_EXCLUSIVE 0
#define STAT_SHARING_MODE_SHARED    1

static jlong normalisedPerformanceMode(int32_t mode) {
    switch (mode) {
        case AAUDIO_PERFORMANCE_MODE_LOW_LATENCY:  return STAT_PERF_MODE_LOW_LATENCY;
        case AAUDIO_PERFORMANCE_MODE_POWER_SAVING: return STAT_PERF_MODE_POWER_SAVING;
        case AAUDIO_PERFORMANCE_MODE_NONE:         return STAT_PERF_MODE_NONE;
        default:                                   return STAT_NA;
    }
}

static jlong normalisedSharingMode(int32_t mode) {
    switch (mode) {
        case AAUDIO_SHARING_MODE_EXCLUSIVE: return STAT_SHARING_MODE_EXCLUSIVE;
        case AAUDIO_SHARING_MODE_SHARED:    return STAT_SHARING_MODE_SHARED;
        default:                            return STAT_NA;
    }
}

/**
 * Snapshot of the counters, for the performance overlay.
 *
 * One call rather than a getter each, because these are read together and only mean anything as a
 * set. Deliberately touches nothing but atomic scalars in ctx: this runs on the UI thread roughly
 * once a second, and ctx->stream is a plain pointer that recoverThread and nativeCleanup both
 * close and clear, so dereferencing it from here would turn a rare race into a routine one. That
 * is why underruns read as not-available in release builds - the figure is derived from
 * getFramesWritten(), which only the threads that own the stream may call.
 */
JNIEXPORT jlongArray JNICALL
Java_com_limelight_binding_audio_NativeAAudioRenderer_nativeGetStats(
        JNIEnv* env, jclass clazz, jlong handle) {
    (void)clazz;

    AAudioRenderer* ctx = (AAudioRenderer*)(intptr_t)handle;
    if (ctx == NULL) {
        return NULL;
    }

#ifdef LC_DEBUG
    jlong underruns = (jlong)atomic_load_explicit(&ctx->underrunCallbacks, memory_order_relaxed);
#else
    jlong underruns = STAT_NA;
#endif

    // The counters are unsigned 32-bit, so they are widened into jlong rather than passed as
    // signed ints, which would present values above 2^31 as negative during a long session. The
    // buffer size is the value cached at open, not a fresh read off the stream, for the reason
    // given above.
    jlong values[] = {
            STAT_BACKEND_AAUDIO,
            normalisedPerformanceMode(
                    atomic_load_explicit(&ctx->actualPerformanceMode, memory_order_relaxed)),
            normalisedSharingMode(
                    atomic_load_explicit(&ctx->actualSharingMode, memory_order_relaxed)),
            (jlong)atomic_load_explicit(&ctx->bufferSizeFrames, memory_order_relaxed),
            underruns,
            (jlong)atomic_load_explicit(&ctx->droppedBuffers, memory_order_relaxed),
            (jlong)atomic_load_explicit(&ctx->recoveryCount, memory_order_relaxed),
    };

    jsize count = (jsize)(sizeof(values) / sizeof(values[0]));
    jlongArray result = (*env)->NewLongArray(env, count);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, count, values);
    }

    return result;
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

    // Stop the callback before anything it touches goes away. Also folds this stream's counters
    // into the session totals, which has to happen before the close.
    retireStream(ctx);

    uint32_t dropped = atomic_load_explicit(&ctx->droppedBuffers, memory_order_relaxed);
    uint32_t recoveries = atomic_load_explicit(&ctx->recoveryCount, memory_order_relaxed);
    uint32_t xruns = atomic_load_explicit(&ctx->xrunCount, memory_order_relaxed);
    uint32_t silence = atomic_load_explicit(&ctx->silenceSamplesTotal, memory_order_relaxed);
    uint64_t framesWritten = atomic_load_explicit(&ctx->framesWrittenTotal, memory_order_relaxed);
    int32_t perfMode = atomic_load_explicit(&ctx->actualPerformanceMode, memory_order_relaxed);
    int32_t sharingMode = atomic_load_explicit(&ctx->actualSharingMode, memory_order_relaxed);

    // The buffer size the session finished on, which is not necessarily the one it started with:
    // recovery replaces the stream, and until applyLowLatencyBufferSize() moved onto that path the
    // replacement silently inherited AAudio's much larger default. Reported here because this is
    // the only surface that exists in a release build - the overlay is the debug-time equivalent,
    // and without it the regression was unobservable in the field.
    int32_t bufferFrames = atomic_load_explicit(&ctx->bufferSizeFrames, memory_order_relaxed);

    // Unconditional, unlike the overlay: this is the line that reaches a bug report, and it runs
    // on every session end rather than only when something crashed. The priority is computed so a
    // session with anything wrong with it is findable in a logcat dump without knowing what to
    // grep for - including a stream that ran perfectly but never got the mode it asked for.
    bool clean = perfMode == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY &&
                 (silence | dropped | recoveries | xruns) == 0;

    __android_log_print(clean ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, LOG_TAG,
                        "AAudio session ended: %s / %s, %d frame buffer, %llu frames written, "
                        "%u xruns, %u silence samples, %u dropped buffers, %u recoveries",
                        performanceModeText((aaudio_performance_mode_t)perfMode),
                        sharingModeText((aaudio_sharing_mode_t)sharingMode), bufferFrames,
                        (unsigned long long)framesWritten, xruns, silence, dropped, recoveries);

#ifdef LC_DEBUG
    // The counted figures, alongside the derived one above. They measure the same silence by
    // different routes, so a disagreement means the derivation is wrong - which is the only way
    // to keep release builds' underrun number honest.
    LOGI("AAudio underrun detail: %u callbacks underran, %u samples counted (%u derived)",
         atomic_load_explicit(&ctx->underrunCallbacks, memory_order_relaxed),
         atomic_load_explicit(&ctx->underrunSamples, memory_order_relaxed), silence);

    // The figure to compare against AudioTrack's line of the same name. Read without the lock:
    // moonlight-common-c joins the decode thread before ArCleanup, so the only writer is gone.
    if (ctx->latencySamples > 0) {
        LOGI("AAudio output latency min/avg/max %.2f/%.2f/%.2f ms over %u samples",
             ctx->latencyMinUs / 1000.0, (double)ctx->latencyTotalUs / ctx->latencySamples / 1000.0,
             ctx->latencyMaxUs / 1000.0, ctx->latencySamples);
    }
    else {
        LOGW("AAudio output latency unavailable: getTimestamp() never returned a usable sample");
    }

    pthread_mutex_destroy(&ctx->streamLock);
#endif

    free(ctx->ring);
    free(ctx);
}
