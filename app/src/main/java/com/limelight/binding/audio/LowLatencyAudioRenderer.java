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
 */
public class LowLatencyAudioRenderer implements AudioRenderer {

    private final boolean enableAAudio;

    // Both are read from the UI thread by getAudioStats() and swapped from the audio decode thread
    // when a dead AAudio stream is dropped, so neither may be cached in a register across the
    // check that guards it.
    private volatile AudioRenderer renderer;
    private volatile NativeAAudioRenderer aaudioRenderer;

    // Retained so we can build an AudioTrack renderer later if the AAudio stream dies mid-session
    private MoonBridge.AudioConfiguration audioConfiguration;
    private int sampleRate;
    private int samplesPerFrame;

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
     * as this renderer's own.
     */
    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        this.audioConfiguration = audioConfiguration;
        this.sampleRate = sampleRate;
        this.samplesPerFrame = samplesPerFrame;

        if (shouldTryAAudio(audioConfiguration)) {
            NativeAAudioRenderer candidate = new NativeAAudioRenderer();
            if (candidate.setup(audioConfiguration, sampleRate, samplesPerFrame) == 0) {
                LimeLog.info("Using native AAudio renderer");
                aaudioRenderer = candidate;
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
     * <p>Also where a dead AAudio stream is noticed and swapped out, since this is the only
     * method called often enough to detect it promptly.
     */
    @Override
    public void playDecodedAudio(short[] audioData) {
        // A route change we couldn't recover from leaves the AAudio stream unusable. Rather than
        // going permanently silent, drop it and let the rest of the session run on AudioTrack.
        if (aaudioRenderer != null && aaudioRenderer.isDead()) {
            LimeLog.warning("AAudio stream died, switching to AudioTrack for the rest of the session");
            aaudioRenderer.cleanup();
            aaudioRenderer = null;

            AudioRenderer fallback = new AndroidAudioRenderer();
            if (fallback.setup(audioConfiguration, sampleRate, samplesPerFrame) != 0) {
                LimeLog.severe("Unable to start AudioTrack fallback");
                renderer = null;
                return;
            }

            fallback.start();
            renderer = fallback;
        }

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

    /** {@inheritDoc} Forwarded to whichever renderer is currently active. */
    @Override
    public void stop() {
        if (renderer != null) {
            renderer.stop();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegated to whichever renderer is currently backing the session, so the overlay follows
     * a mid-session fallback from AAudio to AudioTrack rather than going blank.
     */
    @Override
    public long[] getAudioStats() {
        // Read once: the decode thread can swap this out underneath us in playDecodedAudio.
        AudioRenderer current = renderer;
        return current != null ? current.getAudioStats() : null;
    }

    /** {@inheritDoc} Releases the active renderer, whichever one the session ended up on. */
    @Override
    public void cleanup() {
        if (renderer != null) {
            renderer.cleanup();
            renderer = null;
        }
        aaudioRenderer = null;
    }
}
