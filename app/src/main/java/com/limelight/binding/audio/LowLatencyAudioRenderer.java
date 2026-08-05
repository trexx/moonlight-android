package com.limelight.binding.audio;

import android.content.Context;
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

    private final Context context;
    private final boolean enableAudioFx;
    private final boolean enableAAudio;

    private AudioRenderer renderer;
    private NativeAAudioRenderer aaudioRenderer;

    // Retained so we can build an AudioTrack renderer later if the AAudio stream dies mid-session
    private MoonBridge.AudioConfiguration audioConfiguration;
    private int sampleRate;
    private int samplesPerFrame;

    public LowLatencyAudioRenderer(Context context, boolean enableAudioFx, boolean enableAAudio) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.enableAAudio = enableAAudio;
    }

    private boolean shouldTryAAudio(MoonBridge.AudioConfiguration audioConfiguration) {
        if (!enableAAudio) {
            return false;
        }

        // The low latency path is incompatible with the audio effects pipeline, and the effect
        // control session we open in start()/stop() is keyed on an AudioTrack session ID that
        // AAudio has no equivalent for.
        if (enableAudioFx) {
            LimeLog.info("Not using AAudio because audio effects are enabled");
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

        renderer = new AndroidAudioRenderer(context, enableAudioFx);
        return renderer.setup(audioConfiguration, sampleRate, samplesPerFrame);
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // A route change we couldn't recover from leaves the AAudio stream unusable. Rather than
        // going permanently silent, drop it and let the rest of the session run on AudioTrack.
        if (aaudioRenderer != null && aaudioRenderer.isDead()) {
            LimeLog.warning("AAudio stream died, switching to AudioTrack for the rest of the session");
            aaudioRenderer.cleanup();
            aaudioRenderer = null;

            AudioRenderer fallback = new AndroidAudioRenderer(context, enableAudioFx);
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

    @Override
    public void start() {
        if (renderer != null) {
            renderer.start();
        }
    }

    @Override
    public void stop() {
        if (renderer != null) {
            renderer.stop();
        }
    }

    @Override
    public void cleanup() {
        if (renderer != null) {
            renderer.cleanup();
            renderer = null;
        }
        aaudioRenderer = null;
    }
}
