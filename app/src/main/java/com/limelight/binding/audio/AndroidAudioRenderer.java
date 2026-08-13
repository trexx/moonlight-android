package com.limelight.binding.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Default audio output, backed by {@link AudioTrack}.
 *
 * <p>Used for every stream unless the user has opted into the native AAudio path and it is
 * viable; see {@link LowLatencyAudioRenderer}, which picks between the two.
 *
 * <p>Setup is a search rather than a single attempt: buffers smaller than
 * {@link AudioTrack#getMinBufferSize} and {@code PERFORMANCE_MODE_LOW_LATENCY} both cut latency
 * but are rejected on some devices, so the four combinations are tried from lowest to highest
 * latency and the first one that plays wins.
 *
 * @see AudioRenderer for the lifecycle and threading contract
 */
public class AndroidAudioRenderer implements AudioRenderer {

    private AudioTrack track;

    // What the platform actually granted, read back once at setup so nothing has to touch the
    // track afterwards. Until now the only record of the configuration was what we asked for,
    // which says nothing: AudioTrack may decline PERFORMANCE_MODE_LOW_LATENCY without saying so.
    private int grantedPerformanceMode = AudioTrack.PERFORMANCE_MODE_NONE;
    private int grantedBufferFrames;
    private int acceptedAttempt;

    // Buffers dropped because too much audio was already pending. Written only by the audio decode
    // thread in playDecodedAudio; volatile so the teardown summary sees the final value.
    private volatile int droppedBuffers;

    // Debug builds only; see playDecodedAudio. How long the blocking write actually blocked is the
    // direct measure of output backpressure on this path, and the only thing that says whether the
    // 40 ms pending-audio bound is being reached in practice.
    private long writeBlockedTotalNs;
    private long writeBlockedMaxNs;
    private int writeCount;

    // Debug builds only; see sampleOutputLatency(). End-to-end output latency is the figure that
    // makes this path comparable with AAudio's: both are granted the same performance mode and the
    // same buffer size on the supported hardware, so whatever separates them is upstream of the
    // sink and does not show up in the configuration at all.
    //
    // Allocated once at setup rather than per sample - this is only ever touched from the audio
    // decode thread, but that thread is a hot path and allocating on it would be a regression even
    // in a debug build.
    private AudioTimestamp outputTimestamp;
    private int sampleRate;
    private int channelCount;
    private long framesWritten;
    private int latencySampleCountdown;
    private int latencySampleInterval;
    private long latencyMinUs = Long.MAX_VALUE;
    private long latencyMaxUs;
    private long latencyTotalUs;
    private int latencySamples;

    /** Names what {@link AudioTrack#getPerformanceMode} returned, which is not what was asked for. */
    private static String performanceModeText(int performanceMode) {
        switch (performanceMode) {
            case AudioTrack.PERFORMANCE_MODE_LOW_LATENCY:
                return "low latency";
            case AudioTrack.PERFORMANCE_MODE_POWER_SAVING:
                return "power saving";
            case AudioTrack.PERFORMANCE_MODE_NONE:
                return "none";
            default:
                return "unknown";
        }
    }

    /**
     * @param lowLatency request {@code PERFORMANCE_MODE_LOW_LATENCY}, which the platform may
     *                   silently decline
     * @throws RuntimeException if the platform rejects this combination of parameters
     */
    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                .setAudioFormat(format)
                .setAudioAttributes(attributesBuilder.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize);

        if (lowLatency) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }

        return trackBuilder.build();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Tries the four buffer and performance mode combinations from lowest to highest latency,
     * keeping the first that the platform accepts.
     */
    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        // Retained for the latency measurement below, which needs both to turn a frame count into
        // a duration. Cheap to keep either way, so they are not behind the debug guard.
        this.sampleRate = sampleRate;
        this.channelCount = audioConfiguration.channelCount;

        if (BuildConfig.DEBUG) {
            outputTimestamp = new AudioTimestamp();

            // One sample a second. Buffers arrive at sampleRate/samplesPerFrame per second - 200/s
            // for the 5 ms frames moonlight-common-c sends at 48 kHz - so derive the interval
            // rather than hardcoding it, since a surround configuration changes the frame size.
            latencySampleInterval = Math.max(1, sampleRate / samplesPerFrame);
            latencySampleCountdown = latencySampleInterval;
        }

        // 2 bytes per sample, since the format is fixed at 16-bit PCM
        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // The fast mixer only runs at the device's native rate, so asking for low latency
            // at any other rate silently gets a resampler and none of the benefit
            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                //
                // Read back what was granted rather than repeating what was requested. Which of
                // the four combinations won was previously unrecoverable from the log too, so
                // "small buffer, low latency" and "large buffer, standard" looked the same.
                acceptedAttempt = i + 1;
                grantedPerformanceMode = track.getPerformanceMode();
                grantedBufferFrames = track.getBufferSizeInFrames();

                // info(), so proguard-rules.pro strips this from release builds along with every
                // other LimeLog.info call. That is deliberate and the right trade here: the
                // release-build answers are the warning below when something is wrong, and the
                // performance overlay when it is not. Unlike the AAudio renderer, which logs
                // natively and so keeps its equivalent line in release, this one is debug-only.
                LimeLog.info("Audio track configuration: attempt "+acceptedAttempt+"/4, "
                        +grantedBufferFrames+" frame buffer granted (requested "+bufferSize
                        +" bytes), performance mode "+performanceModeText(grantedPerformanceMode));

                // The failure this renderer cannot do anything about, and the reason the AAudio
                // path exists: the request is accepted, playback works, and the output is half a
                // second behind. There is nothing better to fall back to here - the search above
                // has already picked the best combination the platform would take - so say so and
                // carry on.
                if (lowLatency && grantedPerformanceMode != AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
                    LimeLog.warning("AudioTrack declined PERFORMANCE_MODE_LOW_LATENCY and granted "
                            +performanceModeText(grantedPerformanceMode)+" instead. Audio output "
                            +"latency will be higher than requested.");
                }
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link AudioTrack#write(short[], int, int)} blocks once the track's buffer is full, so
     * the pending-duration check below keeps a slow or stalled output from backing moonlight-common-c's
     * receive queue up without limit. It bounds that queue, not the total output latency - see there.
     */

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack is buffering for us.
        if (MoonBridge.getPendingAudioDuration() < 40) {
            // This will block until the write is completed. That can cause a backlog of pending
            // audio data, so the check above bounds it at 40 ms.
            //
            // What that bounds is LiGetPendingAudioDuration(), which is moonlight-common-c's own
            // receive queue - packets that arrived but have not reached us yet. It says nothing
            // about what is already inside AudioTrack and the HAL below it, and that is where the
            // latency actually lives: on a device that is denied the fast path, the sink alone
            // holds around 170 ms, measured. Dropping incoming buffers cannot recover any of it,
            // since the backlog is downstream of this point - flushing the track would glitch and
            // then refill to the same depth, because that depth is the buffer the platform granted.
            //
            // So this is not a latency bound, and there is no client-side policy that would make
            // it one. A deep sink is reported instead: the downgrade warning in setup() names it,
            // and the session summary carries the measured figure.
            //
            // The timing either side is compiled out of release builds: BuildConfig.DEBUG is a
            // compile-time constant, so the ternary folds to 0 and the block below disappears.
            // This runs on the audio decode thread once per buffer, which is a hot path, and
            // instrumentation on a hot path does not ship.
            long writeStartNs = BuildConfig.DEBUG ? System.nanoTime() : 0;

            track.write(audioData, 0, audioData.length);

            if (BuildConfig.DEBUG) {
                recordWriteBlocked(System.nanoTime() - writeStartNs);

                framesWritten += audioData.length / channelCount;

                // Throttled to once a second: getTimestamp() is a real call into AudioFlinger, not
                // a field read, so it has no business running per buffer even in a debug build.
                if (--latencySampleCountdown <= 0) {
                    latencySampleCountdown = latencySampleInterval;
                    sampleOutputLatency();
                }
            }
        }
        else {
            // Counted in the branch that already logs, so this costs nothing a healthy stream
            // would notice. AAudio's renderer counts the same event on its side, which is what
            // makes the two output paths comparable.
            droppedBuffers++;
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
        }
    }

    /**
     * Accumulates one blocking-write measurement. Only ever called from a {@code BuildConfig.DEBUG}
     * branch, and only from the audio decode thread, so the plain fields need no synchronisation.
     */
    private void recordWriteBlocked(long blockedNs) {
        writeBlockedTotalNs += blockedNs;
        writeCount++;

        if (blockedNs > writeBlockedMaxNs) {
            writeBlockedMaxNs = blockedNs;
        }
    }

    /**
     * Samples how long it will be before the audio just written is actually heard.
     *
     * <p>This is the only figure that distinguishes this path from the AAudio one on the supported
     * hardware, where both are granted {@code PERFORMANCE_MODE_LOW_LATENCY} and the same buffer
     * size. The buffer size alone is not the answer: it bounds what the sink holds, not what is
     * queued in front of it.
     *
     * <p>Debug builds only, and only from the audio decode thread, so the plain fields need no
     * synchronisation - the same argument as {@link #recordWriteBlocked}.
     */
    private void sampleOutputLatency() {
        if (!track.getTimestamp(outputTimestamp)) {
            // Unavailable until the track has actually presented something, so the first sample or
            // two after start return nothing. Not an error, and not counted as a zero.
            return;
        }

        // framePosition was presented at nanoTime, which is already in the past by the time we are
        // told about it, so project the DAC forward to now. What is still ahead of it is the delay
        // the next frame written will incur.
        long elapsedNs = System.nanoTime() - outputTimestamp.nanoTime;
        long presentedNow = outputTimestamp.framePosition + (elapsedNs * sampleRate / 1000000000L);
        long queuedFrames = framesWritten - presentedNow;

        if (queuedFrames < 0) {
            // The projection overran what we have written, which means the track drained and the
            // timestamp is stale. Discarded rather than clamped to zero: averaging in a zero would
            // quietly drag the figure down and make the path look better than it is.
            return;
        }

        long latencyUs = queuedFrames * 1000000L / sampleRate;

        latencyTotalUs += latencyUs;
        latencySamples++;

        if (latencyUs < latencyMinUs) {
            latencyMinUs = latencyUs;
        }
        if (latencyUs > latencyMaxUs) {
            latencyMaxUs = latencyUs;
        }
    }

    @Override
    public void start() {
        // Playback is already running: setup() calls AudioTrack.play() on the track it settles on.
    }

    @Override
    public void stop() {
        // Nothing to unwind here; cleanup() releases the track.
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pauses and flushes before releasing so that buffered audio is discarded rather than
     * played out after the stream has ended.
     */
    /**
     * {@inheritDoc}
     *
     * <p>Unlike the AAudio path this reports underruns live, because getUnderrunCount() is a call
     * on a Java object whose lifetime the lock below covers - there is no raw pointer to outlive.
     */
    @Override
    public synchronized long[] getAudioStats() {
        if (track == null) {
            return null;
        }

        long[] stats = new long[MoonBridge.AUDIO_STAT_COUNT];
        stats[MoonBridge.AUDIO_STAT_BACKEND] = MoonBridge.AUDIO_BACKEND_AUDIOTRACK;
        stats[MoonBridge.AUDIO_STAT_PERFORMANCE_MODE] = normalisedPerformanceMode();
        stats[MoonBridge.AUDIO_STAT_SHARING_MODE] = MoonBridge.AUDIO_STAT_NA; // AAudio-only concept
        stats[MoonBridge.AUDIO_STAT_BUFFER_FRAMES] = grantedBufferFrames;
        stats[MoonBridge.AUDIO_STAT_UNDERRUNS] = track.getUnderrunCount();
        stats[MoonBridge.AUDIO_STAT_DROPPED_BUFFERS] = droppedBuffers;
        stats[MoonBridge.AUDIO_STAT_RECOVERIES] = MoonBridge.AUDIO_STAT_NA; // AAudio-only concept

        return stats;
    }

    /** Maps AudioTrack's performance mode onto the backend-independent {@link MoonBridge} values. */
    private long normalisedPerformanceMode() {
        switch (grantedPerformanceMode) {
            case AudioTrack.PERFORMANCE_MODE_LOW_LATENCY:
                return MoonBridge.AUDIO_PERF_MODE_LOW_LATENCY;
            case AudioTrack.PERFORMANCE_MODE_POWER_SAVING:
                return MoonBridge.AUDIO_PERF_MODE_POWER_SAVING;
            case AudioTrack.PERFORMANCE_MODE_NONE:
                return MoonBridge.AUDIO_PERF_MODE_NONE;
            default:
                return MoonBridge.AUDIO_STAT_NA;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code synchronized} against {@link #getAudioStats()}, which the overlay calls about once
     * a second: without it that could reach a released track. Deliberately not against
     * {@link #playDecodedAudio}, which runs on the audio decode thread - moonlight-common-c joins
     * that thread before calling ArCleanup, so they never overlap.
     */
    @Override
    public synchronized void cleanup() {
        // setup() returns -2 without a track if every combination was rejected, and the connection
        // is torn down through here either way.
        if (track == null) {
            return;
        }

        // Sampled before the release, since the getters stop working afterwards. getUnderrunCount()
        // is maintained by the framework, so this output path gets its underrun figure exactly and
        // for free - no counting of our own anywhere.
        int underruns = track.getUnderrunCount();

        // Unconditional, and at warning level when anything went wrong, so it survives in a bug
        // report. A session that played perfectly but never got the mode it asked for counts as
        // wrong here: that is the whole failure mode.
        String summary = "AudioTrack session ended: performance mode "
                +performanceModeText(grantedPerformanceMode)+", "+grantedBufferFrames
                +" frame buffer, "+underruns+" underruns, "+droppedBuffers+" dropped buffers";

        if (BuildConfig.DEBUG && writeCount > 0) {
            summary += ", write blocked avg "+(writeBlockedTotalNs / writeCount / 1000)
                    +" us, max "+(writeBlockedMaxNs / 1000)+" us";
        }

        if (BuildConfig.DEBUG && latencySamples > 0) {
            // Microseconds throughout, converted here only: an integer millisecond figure would
            // round away the difference this measurement exists to detect.
            summary += ", output latency min/avg/max "+(latencyMinUs / 1000.0f)+"/"
                    +(latencyTotalUs / latencySamples / 1000.0f)+"/"+(latencyMaxUs / 1000.0f)
                    +" ms over "+latencySamples+" samples";
        }

        if (grantedPerformanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                && underruns == 0 && droppedBuffers == 0) {
            LimeLog.info(summary);
        }
        else {
            LimeLog.warning(summary);
        }

        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
        track = null;
    }
}
