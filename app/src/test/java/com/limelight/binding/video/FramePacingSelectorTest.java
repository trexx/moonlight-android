package com.limelight.binding.video;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Tests for the frame rate and pacing mode chosen against the active display mode.
 *
 * <p>The rates used here are the ones the two supported boxes actually expose. 59.94 and 23.976 are
 * the NTSC modes the Homatics reports over HDMI; 60.000 and 50.000 are what the Shield reports on a
 * PC monitor. Numbers pulled from nowhere would pass just as well and pin nothing.
 */
class FramePacingSelectorTest {

    /** The mode every other one is compared against; only this one consults the display rate. */
    private static final int CAP_FPS = PreferenceConfiguration.FRAME_PACING_CAP_FPS;
    private static final int BALANCED = PreferenceConfiguration.FRAME_PACING_BALANCED;

    @Nested
    @DisplayName("modes other than cap-FPS")
    class PassThrough {

        /**
         * Only cap-FPS derives anything from the display. Every other mode has to reach the host
         * with the rate the user set, because the mismatch is handled when rendering instead — a
         * downgrade here would silently change what those modes mean.
         */
        @ParameterizedTest(name = "pacing mode {0}")
        @ValueSource(ints = {
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY,
                PreferenceConfiguration.FRAME_PACING_BALANCED,
                PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS,
        })
        @DisplayName("pass the request through untouched")
        void passThroughUntouched(int requestedPacing) {
            FramePacingSelector pacing = FramePacingSelector.select(59.94f, 60, requestedPacing);

            assertEquals(60, pacing.frameRate);
            assertEquals(requestedPacing, pacing.framePacing);
            assertEquals(FramePacingSelector.Decision.AS_REQUESTED, pacing.decision);
        }

        /**
         * Streaming below the display rate needs no cap: there is nothing to stay under. 30 on a
         * 60 Hz panel is the ordinary bandwidth-limited case, not an edge one.
         */
        @Test
        @DisplayName("cap-FPS below the display rate also passes through")
        void belowDisplayRatePassesThrough() {
            FramePacingSelector pacing = FramePacingSelector.select(60.0f, 30, CAP_FPS);

            assertEquals(30, pacing.frameRate);
            assertEquals(CAP_FPS, pacing.framePacing);
            assertEquals(FramePacingSelector.Decision.AS_REQUESTED, pacing.decision);
        }
    }

    @Nested
    @DisplayName("cap-FPS against the display rate")
    class CapFps {

        /**
         * More than three frames above the panel cannot be paced to it at all, so the mode is
         * downgraded to dropping rather than left claiming a cap it cannot honour. The three-frame
         * slack is what keeps 60-on-59.94 out of this branch.
         */
        @ParameterizedTest(name = "{0} fps on a {1} Hz display")
        @CsvSource({
                "120, 60.0",
                "64,  60.0",
                "60,  50.0",
        })
        @DisplayName("well above the display rate drops frames instead")
        void wellAboveDisplayRateDrops(int requestedFps, float displayRefreshRate) {
            FramePacingSelector pacing =
                    FramePacingSelector.select(displayRefreshRate, requestedFps, CAP_FPS);

            assertEquals(requestedFps, pacing.frameRate);
            assertEquals(BALANCED, pacing.framePacing);
            assertEquals(FramePacingSelector.Decision.DROP_ABOVE_REFRESH, pacing.decision);
        }

        /**
         * A display reporting 49 Hz or less is not a display mode either box has; it is a bad
         * report. Capping to it would strand the stream at a rate the panel never runs at, so the
         * request is left alone and pacing falls back to legacy rendering.
         */
        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(floats = {1.0f, 24.0f, 30.0f, 49.0f})
        @DisplayName("a bogus display rate falls back to legacy rendering")
        void bogusRateFallsBack(float displayRefreshRate) {
            FramePacingSelector pacing = FramePacingSelector.select(displayRefreshRate, 30, CAP_FPS);

            assertEquals(30, pacing.frameRate);
            assertEquals(BALANCED, pacing.framePacing);
            assertEquals(FramePacingSelector.Decision.BOGUS_REFRESH_RATE, pacing.decision);
        }

        /**
         * 50 Hz is the first rate that is believed, and it is a real mode on both boxes in PAL
         * regions. It sits directly against the 49 Hz rejection above.
         */
        @Test
        @DisplayName("50 Hz is believed and capped normally")
        void fiftyHertzIsBelieved() {
            FramePacingSelector pacing = FramePacingSelector.select(50.0f, 50, CAP_FPS);

            assertEquals(49, pacing.frameRate);
            assertEquals(CAP_FPS, pacing.framePacing);
            assertEquals(FramePacingSelector.Decision.CAPPED_BELOW_REFRESH, pacing.decision);
        }

        /**
         * The regression this branch exists for: requesting 59 against a 59.94 display is 1.6% out,
         * and Sunshine discards clientRefreshRateX100 beyond 1%, so the exact rate was thrown away
         * every time. Asking for the whole number keeps it.
         */
        @ParameterizedTest(name = "{0} Hz requests {1}")
        @CsvSource({
                "59.94,  60",
                "29.97,  30",
                "23.976, 24",
        })
        @DisplayName("a fractional display rate requests the whole number, not one below it")
        void fractionalRateRequestsWholeNumber(float displayRefreshRate, int expectedFrameRate) {
            FramePacingSelector pacing =
                    FramePacingSelector.select(displayRefreshRate, expectedFrameRate, CAP_FPS);

            assertEquals(expectedFrameRate, pacing.frameRate);
            assertEquals(CAP_FPS, pacing.framePacing);
            assertEquals(FramePacingSelector.Decision.FRACTIONAL_RATE, pacing.decision);
        }

        /**
         * A whole-number panel has no exact rate to pass along, so the frame below it is still the
         * right answer. This is the branch the fractional case was wrongly sharing.
         */
        @ParameterizedTest(name = "{0} Hz caps to {1}")
        @CsvSource({
                "60.0,  59",
                "120.0, 119",
                "144.0, 143",
        })
        @DisplayName("a whole-number display rate caps one frame below it")
        void wholeNumberRateCapsOneBelow(float displayRefreshRate, int expectedFrameRate) {
            FramePacingSelector pacing = FramePacingSelector.select(
                    displayRefreshRate, Math.round(displayRefreshRate), CAP_FPS);

            assertEquals(expectedFrameRate, pacing.frameRate);
            assertEquals(CAP_FPS, pacing.framePacing);
            assertEquals(FramePacingSelector.Decision.CAPPED_BELOW_REFRESH, pacing.decision);
        }

        /**
         * Float representation noise must not be read as a fractional mode. 60.0001 Hz is a
         * whole-number panel; the epsilon separates it from 59.94 without catching one for the
         * other, and getting this wrong would request 60 against a panel running 60 and lose the
         * frame of headroom the cap exists to keep.
         */
        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(floats = {60.0f, 60.001f, 59.999f})
        @DisplayName("representation noise around a whole number is not a fractional mode")
        void representationNoiseIsNotFractional(float displayRefreshRate) {
            FramePacingSelector pacing = FramePacingSelector.select(displayRefreshRate, 60, CAP_FPS);

            assertEquals(59, pacing.frameRate);
            assertEquals(FramePacingSelector.Decision.CAPPED_BELOW_REFRESH, pacing.decision);
        }
    }
}
