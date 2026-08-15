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

        switch (audioConfiguration.channelCount) {
            case 2 -> channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            case 4 -> channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
            case 6 -> channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
            case 8 -> channelConfig = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND;
            default -> {
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
            }
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
            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode
            boolean lowLatency = switch (i) {
                case 0, 1 -> true;
                default -> false;
            };

            int bufferSize = switch (i) {
                case 0, 2 -> bytesPerFrame * 2;
                default -> {
                    // Try the larger buffer size, rounded up to the next whole frame
                    int minBufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);
                    yield ((minBufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame;
                }
            };

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
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
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

    // Frames dropped because moonlight-common-c's queue was already too deep. Counted rather than
    // logged as they happen: audio arrives roughly every 5 ms, so logging per drop meant up to two
    // hundred string builds a second on the audio path at precisely the moment it was struggling.
    // The total is reported once at cleanup, which is when it can actually be read.
    private int droppedFrames;

    /**
     * {@inheritDoc}
     *
     * <p>{@link AudioTrack#write(short[], int, int)} blocks once the track's buffer is full, and
     * the check above it bounds how far moonlight-common-c's own decode queue may run ahead when
     * that happens.
     *
     * <p>It does <em>not</em> bound output latency, despite how it reads.
     * {@link MoonBridge#getPendingAudioDuration()} reports that decode queue, not what is sitting
     * inside {@link AudioTrack} — and on a device denied the fast path, the track's own buffer is
     * where the latency actually lives. See {@code HARDWARE_TESTING.md} §3 for the measurement.
     */
    @Override
    public void playDecodedAudio(short[] audioData) {
        // Read once. This is a JNI call and it runs per audio packet, so the old second call in
        // the drop path cost a round trip purely to build a log message.
        int pendingAudioMs = MoonBridge.getPendingAudioDuration();

        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack is buffering for us.
        if (pendingAudioMs < 40) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to bound how deep that
            // queue is allowed to get.
            track.write(audioData, 0, audioData.length);
        }
        else {
            droppedFrames++;
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
        if (droppedFrames != 0) {
            LimeLog.warning("AudioTrack renderer dropped " + droppedFrames +
                    " frames to bound the decode queue depth");
        }

        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
    }
}
