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

    private long handle;

    private static native long nativeSetup(int channelCount, int channelMask, int sampleRate, int samplesPerFrame);
    private static native void nativeEnqueue(long handle, short[] data, int length);
    private static native boolean nativeIsDead(long handle);
    private static native void nativeCleanup(long handle);

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

    @Override
    public void start() {
        // The stream is started as part of setup()
    }

    @Override
    public void stop() {
    }

    @Override
    public void cleanup() {
        if (handle != 0) {
            nativeCleanup(handle);
            handle = 0;
        }
    }
}
