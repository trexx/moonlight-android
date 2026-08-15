package com.limelight.binding.audio;

import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.binding.input.driver.GipController;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Chooses between the native AAudio output path and the long-standing AudioTrack one.
 *
 * AudioTrack remains the default and is used unchanged unless the user has explicitly opted into
 * AAudio, every precondition holds, and the stream actually opens. If any of that fails we fall
 * straight back, so enabling the option can degrade to today's behaviour but never below it.
 *
 * <p>That choice is remade only when the local output is rebuilt: at setup, and each time the
 * audio comes back from a pad to the TV. Route changes — HDMI replug or mode change, switching to
 * a soundbar, a Bluetooth device connecting — never reach here at all. They are recovered inside
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
    private final PadAudioSink padAudioSink;

    private AudioRenderer renderer;

    // Retained so the local renderer can be rebuilt later: every time the audio comes back from a
    // pad to the TV
    private MoonBridge.AudioConfiguration audioConfiguration;
    private int sampleRate;
    private int samplesPerFrame;

    /*
     * Whether pads had the audio last time a buffer arrived.
     *
     * Touched only by the audio decode thread, inside playDecodedAudio(), which is what makes it
     * safe without locking - see the routing note there.
     */
    private boolean padsHadAudio;

    /**
     * @param enableAAudio user opt-in. AAudio is never used without it, since the AudioTrack
     *                     path is the better-tested one on most devices.
     * @param padAudioSink pads currently taking the audio instead of the TV. Starts empty and is
     *                     populated from the in-game menu, so with no pad enabled the local output
     *                     below behaves exactly as it did before this existed.
     */
    public LowLatencyAudioRenderer(boolean enableAAudio, PadAudioSink padAudioSink) {
        this.enableAAudio = enableAAudio;
        this.padAudioSink = padAudioSink;
    }

    /** @return true if every precondition for the AAudio path holds for this stream */
    private boolean shouldTryAAudio(MoonBridge.AudioConfiguration audioConfiguration) {
        if (!enableAAudio) {
            return false;
        }

        // AAudioStreamBuilder_setChannelMask() only exists from API 32. Without it a surround
        // stream has no defined speaker layout, which silences everything but front left/right.
        // Stereo is unambiguous from the channel count alone, so it's still fine below 32.
        if (audioConfiguration.channelCount() > 2 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) {
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

        // A pad can only take 48 kHz stereo, and playDecodedAudio() forwards samples verbatim.
        // Telling the sink now means the menu can grey the option out rather than accepting a
        // selection that would send surround audio to a stereo device.
        padAudioSink.setStreamFormat(audioConfiguration.channelCount(), sampleRate);

        return openLocalRenderer();
    }

    /**
     * Builds the local renderer and starts it, setting {@link #renderer}.
     *
     * <p>Shared by {@link #setup} and by the return from pad audio, so both pick a renderer the
     * same way rather than each rebuilding the choice.
     *
     * @return 0 on success, matching {@link #setup}'s contract
     */
    private int openLocalRenderer() {
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

        AudioRenderer fallback = new AndroidAudioRenderer();
        int result = fallback.setup(audioConfiguration, sampleRate, samplesPerFrame);

        if (result == 0) {
            renderer = fallback;
        }

        return result;
    }

    /**
     * Closes the local renderer, because a pad has taken the audio.
     *
     * <p>The TV output was previously left open and unfed for the whole session, its callback
     * emitting silence sixty times a second for nobody, and reporting ring-buffer overruns when the
     * audio came back to it. Closing it means the stream is genuinely released rather than idling.
     */
    private void closeLocalRenderer() {
        if (renderer == null) {
            return;
        }

        renderer.stop();
        renderer.cleanup();

        // Cleared before anything can reach it again: the delegation at the end of
        // playDecodedAudio() would otherwise write into a handle that has just been freed.
        renderer = null;

        LimeLog.info("Audio moved to a pad; local output closed");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Also where the local output is opened and closed as pads take the audio and give it
     * back, since this is the only method that runs on the thread those handles belong to.
     */
    @Override
    public void playDecodedAudio(short[] audioData) {
        // Pads take the audio instead of the TV, not as well as it - the point is private
        // listening. A single volatile read, and the empty case is the overwhelmingly common one.
        GipController[] pads = padAudioSink.getTargets();
        boolean padsActive = pads.length > 0;

        /*
         * The local renderer is opened and closed here, on this thread, rather than from
         * PadAudioSink where the routing actually changes.
         *
         * The sink's enable() and disable() run on the UI thread from the game menu, so closing the
         * renderer there would race a buffer already inside playDecodedAudio() into a freed native
         * handle. Every lifecycle call therefore happens on the audio decode thread, gated on the
         * same volatile read that routes the samples, which needs no lock at all.
         *
         * The cost on the common path is one boolean comparison per buffer - roughly two hundred a
         * second at the 5 ms Opus frames this negotiates, and nowhere near the frame path.
         */
        if (padsActive != padsHadAudio) {
            padsHadAudio = padsActive;

            if (padsActive) {
                closeLocalRenderer();
            }
            else if (openLocalRenderer() == 0) {
                renderer.start();

                LimeLog.info("Audio returned from the pads; local output reopened");
            }
            else {
                // The same trade the AAudio path makes when its own recovery fails: say so and
                // stay silent, rather than leaving a half-open stream behind.
                LimeLog.severe("Unable to reopen local audio output after pad audio");
                renderer = null;
            }
        }

        if (padsActive) {
            for (GipController pad : pads) {
                pad.queueAudio(audioData, audioData.length);
            }
            return;
        }

        if (renderer != null) {
            renderer.playDecodedAudio(audioData);
        }
    }

    /** {@inheritDoc} Forwarded to whichever renderer {@link #openLocalRenderer} selected. */
    @Override
    public void start() {
        if (renderer != null) {
            renderer.start();
        }
    }

    /** {@inheritDoc} Forwarded to whichever renderer {@link #openLocalRenderer} selected. */
    @Override
    public void stop() {
        if (renderer != null) {
            renderer.stop();
        }
    }

    /** {@inheritDoc} Releases the renderer {@link #openLocalRenderer} selected. */
    @Override
    public void cleanup() {
        // Stop the pads first: their sender threads outlive this object otherwise, and a pad left
        // in audio mode keeps its ring and thread alive for nothing.
        padAudioSink.disableAll();

        if (renderer != null) {
            renderer.cleanup();
            renderer = null;
        }
    }
}
