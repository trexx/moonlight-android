package com.limelight.binding.audio;

import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Chooses between the native AAudio output path and the long-standing AudioTrack one.
 *
 * AudioTrack remains the default and is used unchanged unless the user has explicitly opted into
 * AAudio, every precondition holds, and the stream actually opens. If any of that fails we fall
 * straight back, so enabling the option can degrade to today's behaviour but never below it.
 *
 * <p>That choice is made once, at setup, and is never revisited. Route changes — HDMI replug or
 * mode change, switching to a soundbar, a Bluetooth device connecting — are recovered inside
 * {@code aaudio_renderer.c}: AAudio reports {@code AAUDIO_ERROR_DISCONNECTED} to its error
 * callback, which reopens and restarts the stream on a detached thread. None of that involves
 * this class, and it is unaffected by there being no liveness check here.
 *
 * <p>What is deliberately <em>not</em> handled is the case where that native recovery has already
 * failed. There used to be a poll from {@link #playDecodedAudio} that swapped to AudioTrack for
 * the rest of the session when it did. That is gone: an unrecoverable stream now stays silent,
 * and the user turns the setting off if they hit it. Swapping renderers underneath a running
 * session traded a rare, already-broken case for a JNI round trip on every audio packet, and for
 * a session that silently stops being the thing the user configured.
 */
public class LowLatencyAudioRenderer implements AudioRenderer {

    private final boolean enableAAudio;

    private AudioRenderer renderer;

    /**
     * @param enableAAudio user opt-in. AAudio is never used without it, since the AudioTrack
     *                     path is the better-tested one on most devices.
     */
    public LowLatencyAudioRenderer(boolean enableAAudio) {
        this.enableAAudio = enableAAudio;
    }

    /** @return true if every precondition for the AAudio path holds for this stream */
    private boolean shouldTryAAudio(MoonBridge.AudioConfiguration audioConfiguration) {
        if (!enableAAudio) {
            return false;
        }

        // AAudioStreamBuilder_setChannelMask() only exists from API 32. Without it a surround
        // stream has no defined speaker layout, which silences everything but front left/right.
        // Stereo is unambiguous from the channel count alone, so it's still fine below 32.
        if (audioConfiguration.channelCount > 2 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
            LimeLog.info("Not using AAudio for surround audio below API 32");
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Picks the backing renderer. AAudio is attempted first when eligible; anything short of
     * a working stream falls through to {@link AndroidAudioRenderer}, whose result is returned
     * as this renderer's own. This is the only point at which the choice is made.
     */
    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        if (shouldTryAAudio(audioConfiguration)) {
            NativeAAudioRenderer candidate = new NativeAAudioRenderer();
            if (candidate.setup(audioConfiguration, sampleRate, samplesPerFrame) == 0) {
                LimeLog.info("Using native AAudio renderer");
                renderer = candidate;
                return 0;
            }

            LimeLog.warning("AAudio setup failed, falling back to AudioTrack");
            candidate.cleanup();
        }

        renderer = new AndroidAudioRenderer();
        return renderer.setup(audioConfiguration, sampleRate, samplesPerFrame);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Straight delegation. The null check guards teardown, where {@link #cleanup()} releases
     * the renderer while decoded audio may still be in flight.
     */
    @Override
    public void playDecodedAudio(short[] audioData) {
        if (renderer != null) {
            renderer.playDecodedAudio(audioData);
        }
    }

    /** {@inheritDoc} Forwarded to whichever renderer {@link #setup} selected. */
    @Override
    public void start() {
        if (renderer != null) {
            renderer.start();
        }
    }

    /** {@inheritDoc} Forwarded to whichever renderer {@link #setup} selected. */
    @Override
    public void stop() {
        if (renderer != null) {
            renderer.stop();
        }
    }

    /** {@inheritDoc} Releases the renderer {@link #setup} selected. */
    @Override
    public void cleanup() {
        if (renderer != null) {
            renderer.cleanup();
            renderer = null;
        }
    }
}
