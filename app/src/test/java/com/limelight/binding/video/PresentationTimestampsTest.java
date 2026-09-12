package com.limelight.binding.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Tests for the clock the decoder's input timestamps are drawn from, and the uniqueness rule.
 *
 * <p>The magnitudes used here are the ones the two clocks actually produce: a session-relative
 * enqueue time is hundreds of seconds, {@code System.nanoTime() / 1000} on a box that has been up
 * for weeks is around 1.7e12 µs. A value pulled from nowhere would pass just as well and pin
 * nothing.
 */
class PresentationTimestampsTest {

    /** {@code System.nanoTime() / 1000} on the Shield at twenty days of uptime. */
    private static final long MONOTONIC_US = 1_728_112_161_887L;

    @Nested
    @DisplayName("useMonotonicClock()")
    class Clock {

        /**
         * Only min-latency asks SurfaceFlinger to drop stale frames, so it is the only mode whose
         * PTS must land inside SurfaceFlinger's one-second window. Extending this to another mode
         * would let the Shield drop frames in a mode whose whole point is never to.
         */
        @Test
        @DisplayName("min-latency uses the monotonic clock")
        void minLatencyUsesMonotonicClock() {
            assertTrue(PresentationTimestamps.useMonotonicClock(PreferenceConfiguration.FRAME_PACING_MIN_LATENCY));
        }

        @ParameterizedTest(name = "pacing mode {0}")
        @ValueSource(ints = {
                PreferenceConfiguration.FRAME_PACING_BALANCED,
                PreferenceConfiguration.FRAME_PACING_CAP_FPS,
                PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS,
        })
        @DisplayName("every other mode keeps the session-relative enqueue time")
        void otherModesKeepEnqueueTime(int framePacing) {
            assertFalse(PresentationTimestamps.useMonotonicClock(framePacing));
        }
    }

    @Nested
    @DisplayName("strictlyAfter()")
    class Uniqueness {

        @Test
        @DisplayName("a later candidate is used as is")
        void laterCandidatePassesThrough() {
            assertEquals(387_098_305L, PresentationTimestamps.strictlyAfter(387_098_305L, 387_081_640L));
        }

        /** The value SurfaceFlinger is going to compare against its own clock must not be altered. */
        @Test
        @DisplayName("a monotonic-clock magnitude round-trips unchanged")
        void monotonicMagnitudeRoundTrips() {
            assertEquals(MONOTONIC_US, PresentationTimestamps.strictlyAfter(MONOTONIC_US, MONOTONIC_US - 16_667));
        }

        /**
         * An IDR arrives as several decode units sharing one enqueue time, so a tie is the normal
         * case. MediaCodec rejects a repeated timestamp, and the decode-latency ring matches output
         * to input by exact equality, so each unit needs its own value.
         */
        @Test
        @DisplayName("a tie is bumped by one microsecond")
        void tieIsBumped() {
            assertEquals(387_098_306L, PresentationTimestamps.strictlyAfter(387_098_305L, 387_098_305L));
        }

        @Test
        @DisplayName("an earlier candidate is bumped past the last timestamp, not used")
        void earlierCandidateIsBumped() {
            assertEquals(387_098_306L, PresentationTimestamps.strictlyAfter(387_000_000L, 387_098_305L));
        }

        /** Four units of one IDR: VPS, SPS, PPS and picture data, all stamped at the same instant. */
        @Test
        @DisplayName("a run of ties stays strictly increasing")
        void runOfTiesStaysStrictlyIncreasing() {
            long last = 0;
            for (int i = 0; i < 4; i++) {
                long next = PresentationTimestamps.strictlyAfter(MONOTONIC_US, last);
                assertTrue(next > last, "unit " + i + " did not advance");
                last = next;
            }
            assertEquals(MONOTONIC_US + 3, last);
        }

        /** The first submission of a stream: nothing has gone before, so the field starts at zero. */
        @Test
        @DisplayName("the first timestamp of a stream is used as is")
        void firstTimestampPassesThrough() {
            assertEquals(MONOTONIC_US, PresentationTimestamps.strictlyAfter(MONOTONIC_US, 0));
        }
    }
}
