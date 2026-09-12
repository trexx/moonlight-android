package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Chooses the clock the decoder's input timestamps are drawn from, and keeps them unique.
 *
 * <p>Split out of {@code MediaCodecDecoderRenderer} so the decision can be tested without a codec
 * or a display — the same reason {@code FramePacingSelector} is a separate class. Nothing here
 * touches an Android type: the {@code FRAME_PACING_*} values are compile-time constants that javac
 * inlines, so no reference to {@link PreferenceConfiguration} survives into the bytecode, and there
 * is no {@code LimeLog}. Both methods are static so the submit path pays a call the JIT inlines
 * rather than a virtual dispatch. {@link #useMonotonicClock} runs once per stream;
 * {@link #strictlyAfter} runs once per decode unit.
 *
 * <h2>Why the clock matters</h2>
 * The presentation timestamp handed to {@code queueInputBuffer()} should have no bearing on
 * presentation: every pacing mode passes its own render timestamp to
 * {@code releaseOutputBuffer()}, and that is what SurfaceFlinger is meant to see. On the Shield
 * it is not what SurfaceFlinger sees. NVIDIA's ACodec ("Enable timestamp filtering for Video
 * Decoder" in logcat) substitutes the buffer's PTS for the render timestamp, so
 * {@code dumpsys SurfaceFlinger --latency} reports the input PTS as every frame's desired present
 * time. HARDWARE_TESTING.md section 30 has the dumps.
 *
 * <p>SurfaceFlinger drops the older of two queued frames only when the newer one's desired
 * present time falls within the second before the vsync being considered. Anything further in
 * the past is treated as bogus and disables dropping: every queued frame is then shown, one per
 * vsync, in order. moonlight-common-c's {@code enqueueTimeUs} counts from library start, so it is
 * already tens of seconds behind SurfaceFlinger's clock moments after connecting. A frame that
 * misses its vsync therefore stays a vsync late until the host's clock drifts it out — about
 * 40 s at the drift measured on the Shield — and a four-vsync local stall held two extra frames
 * of latency for twelve seconds.
 *
 * <p>In min-latency pacing that backlog is exactly what {@code releaseOutputBuffer(index,
 * System.nanoTime())} exists to shed, so there the PTS is drawn from that same clock:
 * {@code CLOCK_MONOTONIC}, shared by {@code System.nanoTime()}, Choreographer and SurfaceFlinger.
 * Not {@code CLOCK_MONOTONIC_RAW}, which common-c uses — NTP slew separates the two by seconds on
 * a box that has been up for weeks, and a PTS ahead of SurfaceFlinger's clock would make it hold
 * frames rather than drop them.
 *
 * <p>The other three modes keep the session-relative PTS on purpose. Cap-FPS and max-smoothness
 * release with a timestamp of zero so that nothing is ever dropped; if the Shield substitutes the
 * PTS there too, a timely one would let SurfaceFlinger drop frames against that setting. Balanced
 * never has two frames queued, so it has nothing to gain.
 */
public final class PresentationTimestamps {

    private PresentationTimestamps() {
    }

    /**
     * @param framePacing the {@code PreferenceConfiguration.FRAME_PACING_*} mode in use
     * @return true if input timestamps should come from {@code System.nanoTime()} rather than
     *         from moonlight-common-c's enqueue time
     */
    public static boolean useMonotonicClock(int framePacing) {
        return framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
    }

    /**
     * Keeps timestamps strictly increasing. MediaCodec cannot take two buffers with the same
     * timestamp, and an IDR frame arrives as several decode units — VPS, SPS, PPS, picture data —
     * that share one enqueue time, so a tie is the normal case rather than a corner one.
     *
     * @param candidateUs the timestamp the caller would like to use
     * @param lastUs      the timestamp most recently submitted
     * @return {@code candidateUs} if it is later than {@code lastUs}, otherwise {@code lastUs + 1}
     */
    public static long strictlyAfter(long candidateUs, long lastUs) {
        return candidateUs > lastUs ? candidateUs : lastUs + 1;
    }
}
