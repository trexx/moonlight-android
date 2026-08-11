package com.limelight.nvstream.av.audio;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * Sink for decoded audio, driven by moonlight-common-c through {@link MoonBridge}.
 *
 * <p>The implementation registered with {@code MoonBridge.setupBridge()} owns audio output for
 * the whole session. Callbacks arrive from native threads, not the UI thread, and the samples
 * have already been Opus-decoded to interleaved 16-bit PCM by the time they get here.
 *
 * <h2>Lifecycle</h2>
 * {@link #setup} once, then {@link #start}, then any number of {@link #playDecodedAudio} calls,
 * then {@link #stop} and {@link #cleanup}. A failed {@code setup} tears the connection down, so
 * an implementation that can't honour the requested configuration should fall back to one it
 * can rather than failing outright.
 *
 * <h2>Latency</h2>
 * {@link #playDecodedAudio} runs on the audio decode thread; blocking in it stalls decoding and
 * builds a backlog that the user hears as growing lag. Implementations bound that by checking
 * {@link MoonBridge#getPendingAudioDuration()} and dropping samples rather than queueing without
 * limit, since discarding a few milliseconds is less disruptive than drifting permanently
 * behind the video.
 */
public interface AudioRenderer {
    /**
     * Prepares the output for the negotiated stream format.
     *
     * @param audioConfiguration channel count and channel mask the host will send
     * @param sampleRate         sample rate in Hz
     * @param samplesPerFrame    samples per channel in each {@link #playDecodedAudio} call,
     *                           which sets the natural buffer granularity
     * @return 0 on success; any other value aborts the connection
     */
    int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame);

    /** Called once playback should begin, after {@link #setup} succeeded. */
    void start();

    /** Called when playback is ending, before {@link #cleanup}. */
    void stop();

    /**
     * Consumes one frame of decoded PCM. Called from the audio decode thread.
     *
     * @param audioData interleaved 16-bit samples, {@code channelCount * samplesPerFrame} long.
     *                  The buffer is reused by the caller, so it must not be retained.
     */
    void playDecodedAudio(short[] audioData);

    /** Releases all output resources. Nothing else is called afterwards. */
    void cleanup();

    /**
     * Snapshot of this renderer's own output counters, for diagnostics.
     *
     * <p>Indices, backend ids and the normalised mode values are the {@code AUDIO_STAT_*},
     * {@code AUDIO_BACKEND_*} and {@code AUDIO_PERF_MODE_*} constants on {@link MoonBridge}.
     * Entries the backend has no concept of read {@link MoonBridge#AUDIO_STAT_NA}, which is
     * distinct from a genuine count of zero.
     *
     * <p>Called about once a second from the UI thread while the performance overlay is visible,
     * so implementations must tolerate being called concurrently with {@link #cleanup}.
     *
     * @return the counters, or null if this renderer keeps none. Null is meaningful: it says
     *         there is nothing to claim rather than that something went wrong.
     */
    default long[] getAudioStats() {
        return null;
    }
}
