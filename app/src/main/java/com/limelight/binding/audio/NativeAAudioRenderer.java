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
    private static native boolean nativeIsDead(long handle);
    private static native void nativeCleanup(long handle);
    private static native long[] nativeGetStats(long handle);

    /**
     * {@inheritDoc}
     *
     * <p>Underruns read as {@link MoonBridge#AUDIO_STAT_NA} in release builds. The figure is
     * derived from the stream's own frame counter, which only the threads that own the stream may
     * read, so it is reported in the session-end log rather than live; debug builds count it in
     * the callback and report it here too.
     *
     * <p>{@code synchronized} against {@link #cleanup()}, which zeroes the handle: without it a
     * caller could read a live handle and then pass a freed pointer into JNI. Both are rare - once
     * a session and once a second - so the monitor is uncontended.
     */
    @Override
    public synchronized long[] getAudioStats() {
        return handle != 0 ? nativeGetStats(handle) : null;
    }

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
        handle = nativeSetup(audioConfiguration.channelCount, audioConfiguration.channelMask,
                sampleRate, samplesPerFrame);
        if (handle == 0) {
            LimeLog.warning("Unable to set up AAudio stream");
            return -1;
        }

        return 0;
    }

    /**
     * @return true if the stream has failed unrecoverably and the caller should stop using it
     */
    public boolean isDead() {
        return handle == 0 || nativeIsDead(handle);
    }

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

        // Mirrors AndroidAudioRenderer: bound how far behind we can fall by dropping rather than
        // queueing once there's already 40 ms outstanding.
        if (MoonBridge.getPendingAudioDuration() < 40) {
            nativeEnqueue(handle, audioData, audioData.length);
        }
        else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() + " ms");
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

    /**
     * {@inheritDoc} Stops and closes the stream and frees the native context. Idempotent.
     *
     * <p>{@code synchronized} against {@link #getAudioStats()}; see there. Deliberately not
     * against {@link #playDecodedAudio} or {@link #isDead}, which run on the audio decode thread -
     * moonlight-common-c joins that thread before calling ArCleanup, so there is no window where
     * they overlap with this, and taking a lock per audio buffer would be real cost for none.
     */
    @Override
    public synchronized void cleanup() {
        if (handle != 0) {
            nativeCleanup(handle);
            handle = 0;
        }
    }
}
