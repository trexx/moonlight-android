package com.limelight.binding.video;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Decides the frame rate to request from the host, and whether the requested pacing mode survives
 * contact with the display's actual refresh rate.
 *
 * <p>Split out of {@code Game} so the decision can be exercised without an {@code Activity}, a
 * {@code Display} or a host — the same reason {@code GlRendererParser} and {@code StickCalibration}
 * are separate classes. Nothing here touches an Android type: the {@code FRAME_PACING_*} values are
 * compile-time constants, so javac inlines them and no reference to {@link PreferenceConfiguration}
 * survives into the bytecode.
 *
 * <p>Runs once per stream, immediately before {@code StreamConfiguration} is built.
 */
public final class FramePacingSelector {

    /**
     * How far a reported refresh rate may sit from a whole number and still be treated as that
     * whole number. The fractional modes both target boxes expose — 59.94, 29.97, 23.976 — are
     * 0.024 to 0.06 away from theirs, so this separates them from float representation noise on a
     * panel that genuinely runs at 60.000 without any risk of catching one for the other.
     */
    private static final float FRACTIONAL_RATE_EPSILON = 0.005f;

    /** The frame rate to ask the host to encode at. */
    public final int frameRate;

    /** The pacing mode to actually use, which may be a downgrade of the one requested. */
    public final int framePacing;

    private FramePacingSelector(int frameRate, int framePacing) {
        this.frameRate = frameRate;
        this.framePacing = framePacing;
    }

    /**
     * @param displayRefreshRate the active display mode's refresh rate in Hz
     * @param requestedFps       the frame rate the user asked for
     * @param requestedPacing    the {@code PreferenceConfiguration.FRAME_PACING_*} mode requested
     * @return the frame rate and pacing mode to use
     */
    public static FramePacingSelector select(float displayRefreshRate, int requestedFps, int requestedPacing) {
        // Only "cap FPS" derives anything from the display rate. Every other mode asks for what the
        // user set and handles the mismatch when rendering.
        if (requestedPacing != PreferenceConfiguration.FRAME_PACING_CAP_FPS || requestedFps < Math.round(displayRefreshRate)) {
            return new FramePacingSelector(requestedFps, requestedPacing);
        }

        int roundedRefreshRate = Math.round(displayRefreshRate);

        if (requestedFps > roundedRefreshRate + 3) {
            // Use frame drops when rendering above the screen frame rate
            LimeLog.info("Using drop mode for FPS > Hz");
            return new FramePacingSelector(requestedFps, PreferenceConfiguration.FRAME_PACING_BALANCED);
        }

        if (roundedRefreshRate <= 49) {
            // Let's avoid clearly bogus refresh rates and fall back to legacy rendering
            LimeLog.info("Bogus refresh rate: " + roundedRefreshRate);
            return new FramePacingSelector(requestedFps, PreferenceConfiguration.FRAME_PACING_BALANCED);
        }

        // On a fractional-rate display, ask for the whole number and let clientRefreshRateX100
        // carry the exact rate. Sunshine turns 5994 into 30000/1001 and encodes at precisely that
        // (video.h, framerateX100_to_rational), so the stream already lands on the display rate and
        // the frame we would otherwise subtract to stay under it buys nothing.
        //
        // It also costs something: Sunshine discards clientRefreshRateX100 outright when it differs
        // from the requested rate by more than 1% (rtsp.cpp, "Discard framerateX100 if the derived
        // fps is not within 1% of framerate"). Requesting 59 against a 59.94 display is 1.6% out,
        // so the exact rate was being thrown away every time and the host fell back to integer 59.
        if (isFractional(displayRefreshRate, roundedRefreshRate)) {
            LimeLog.info("Fractional display rate " + displayRefreshRate + "; requesting " + roundedRefreshRate);
            return new FramePacingSelector(roundedRefreshRate, requestedPacing);
        }

        // A whole-number display has no exact rate to pass, so stay a frame below it as before.
        int cappedFrameRate = roundedRefreshRate - 1;
        LimeLog.info("Adjusting FPS target for screen to " + cappedFrameRate);
        return new FramePacingSelector(cappedFrameRate, requestedPacing);
    }

    private static boolean isFractional(float displayRefreshRate, int roundedRefreshRate) {
        return Math.abs(displayRefreshRate - roundedRefreshRate) > FRACTIONAL_RATE_EPSILON;
    }
}
