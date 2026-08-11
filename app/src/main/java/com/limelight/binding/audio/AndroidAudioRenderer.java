package com.limelight.binding.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

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
     * the pending-duration check above it is what keeps a slow or stalled output from turning
     * into unbounded latency.
     */

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack is buffering for us.
        if (MoonBridge.getPendingAudioDuration() < 40) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to be able to bound
            // latency at 40 ms in that situation.
            track.write(audioData, 0, audioData.length);
        }
        else {
            // Counted in the branch that already logs, so this costs nothing a healthy stream
            // would notice. AAudio's renderer counts the same event on its side, which is what
            // makes the two output paths comparable.
            droppedBuffers++;
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
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
    @Override
    public void cleanup() {
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
