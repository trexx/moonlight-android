package com.limelight.binding.audio;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Audio renderer backed by a native AAudio stream.
 *
 * This exists because AudioTrack's fast path is denied on some Android TV devices even when it is
 * requested, leaving audio around a second behind the video. It is never selected on its own —
 * {@link LowLatencyAudioRenderer} decides when it is appropriate and falls back to
 * {@link AndroidAudioRenderer} whenever it isn't.
 */
public class NativeAAudioRenderer implements AudioRenderer {

    // No library load here: these natives live in libmoonlight-core, which MoonBridge's static
    // initializer owns. Nothing can reach this class before that has run, since the renderer is
    // only ever created for a connection that moonlight-common-c is already driving.

    // Opaque pointer to the native AAudioRenderer struct, or 0 once released. Every native call
    // below is a no-op or a failure without it, so it doubles as the "is set up" flag.
    private long handle;

    private static native long nativeSetup(int channelCount, int channelMask, int sampleRate, int samplesPerFrame);
    private static native void nativeEnqueue(long handle, short[] data, int length);
    private static native void nativeCleanup(long handle);

    /**
     * {@inheritDoc}
     *
     * <p>Opens and starts the AAudio stream. Failure here is expected on devices where the
     * requested configuration isn't available, and is the caller's cue to fall back.
     */
    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        // The channel mask moonlight-common-c computes uses the same bit layout as AAudio's, so
        // it can be handed over untranslated. Passing it is what keeps centre/LFE/rear channels
        // alive on surround configurations.
        handle = nativeSetup(audioConfiguration.channelCount(), audioConfiguration.channelMask(),
                sampleRate, samplesPerFrame);
        if (handle == 0) {
            LimeLog.warning("Unable to set up AAudio stream");
            return -1;
        }

        return 0;
    }

    // Frames dropped because moonlight-common-c's queue was already too deep. Counted rather than
    // logged as they happen, for the same reason as in AndroidAudioRenderer: this runs per audio
    // packet, so a backlog meant a string build every 5 ms on the audio path.
    private int droppedFrames;

    /**
     * {@inheritDoc}
     *
     * <p>Copies into the native ring buffer and returns; the samples are consumed later by
     * AAudio's realtime callback. Unlike the AudioTrack path this never blocks, so a full ring
     * drops the frame rather than back-pressuring the decode thread.
     */
    @Override
    public void playDecodedAudio(short[] audioData) {
        if (handle == 0) {
            return;
        }

        // Read once - a JNI call on a path that runs per audio packet.
        int pendingAudioMs = MoonBridge.getPendingAudioDuration();

        // Mirrors AndroidAudioRenderer: bound how deep moonlight-common-c's decode queue may get
        // by dropping rather than queueing once there's already 40 ms outstanding. As there, this
        // bounds that queue and not the stream's own buffering.
        if (pendingAudioMs < 40) {
            nativeEnqueue(handle, audioData, audioData.length);
        }
        else {
            droppedFrames++;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        // The stream is started as part of setup()
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nothing to do: there is no effect session to close, and the stream is stopped as part
     * of {@link #cleanup()} instead, so that samples still in the ring buffer play out.
     */
    @Override
    public void stop() {
    }

    /** {@inheritDoc} Stops and closes the stream and frees the native context. Idempotent. */
    @Override
    public void cleanup() {
        if (droppedFrames != 0) {
            LimeLog.warning("AAudio renderer dropped " + droppedFrames +
                    " frames to bound the decode queue depth");
            droppedFrames = 0;
        }

        if (handle != 0) {
            nativeCleanup(handle);
            handle = 0;
        }
    }
}
