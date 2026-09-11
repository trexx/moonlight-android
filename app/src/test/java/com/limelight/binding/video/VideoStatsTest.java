package com.limelight.binding.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Tests for the perf counters behind the performance overlay and the end-of-stream summary.
 *
 * <p>These numbers are the ruler every other performance judgement is made against, so the
 * arithmetic is worth pinning down - particularly {@link VideoStats#add} treating a zero minimum
 * as "unset", which is the one branch here that is easy to get backwards and impossible to notice
 * by eye on a running stream.
 */
class VideoStatsTest {

    /** A window with every counter set to a distinct value, so a field mix-up cannot hide. */
    private static VideoStats populated() {
        VideoStats stats = new VideoStats();
        stats.decoderTimeMs = 100;
        stats.totalTimeMs = 200;
        stats.totalFrames = 60;
        stats.totalFramesReceived = 58;
        stats.totalFramesRendered = 57;
        stats.frameLossEvents = 2;
        stats.framesLost = 3;
        stats.minHostProcessingLatency = 40;
        stats.maxHostProcessingLatency = 90;
        stats.totalHostProcessingLatency = 1500;
        stats.framesWithHostProcessingLatency = 25;
        stats.worstRecvToEnqueueUs = 4200;
        stats.worstDecoderTimeUs = 7300;
        stats.presentationGapCount = 4;
        stats.worstPresentationGapNanos = 51000000;
        stats.measurementStartTimestamp = 1000;
        return stats;
    }

    @Nested
    @DisplayName("add()")
    class Add {

        @Test
        @DisplayName("sums the cumulative counters")
        void sumsCumulativeCounters() {
            VideoStats target = populated();
            target.add(populated());

            assertEquals(200, target.decoderTimeMs);
            assertEquals(400, target.totalTimeMs);
            assertEquals(120, target.totalFrames);
            assertEquals(116, target.totalFramesReceived);
            assertEquals(114, target.totalFramesRendered);
            assertEquals(4, target.frameLossEvents);
            assertEquals(6, target.framesLost);
            assertEquals(3000, target.totalHostProcessingLatency);
            assertEquals(50, target.framesWithHostProcessingLatency);
        }

        @Test
        @DisplayName("takes the lower of two set minimums")
        void takesLowerMinimum() {
            VideoStats target = populated();
            VideoStats other = populated();
            other.minHostProcessingLatency = 10;

            target.add(other);

            assertEquals(10, target.minHostProcessingLatency);
        }

        @Test
        @DisplayName("does not let an unset minimum pin the result to zero")
        void unsetMinimumDoesNotPinToZero() {
            // A window in which no frame carried host latency has minHostProcessingLatency == 0.
            // Treating that as a real measurement would peg the overlay's minimum at 0.0 ms for
            // the rest of the session, which is exactly the bug the branch in add() avoids.
            VideoStats target = populated();
            VideoStats noHostLatency = new VideoStats();

            target.add(noHostLatency);

            assertEquals(40, target.minHostProcessingLatency);
        }

        @Test
        @DisplayName("adopts the other window's minimum when this one has none")
        void adoptsOtherMinimumWhenUnset() {
            VideoStats target = new VideoStats();

            target.add(populated());

            assertEquals(40, target.minHostProcessingLatency);
        }

        @Test
        @DisplayName("takes the higher maximum, with zero as a legitimate value")
        void takesHigherMaximum() {
            VideoStats target = populated();
            VideoStats other = populated();
            other.maxHostProcessingLatency = 120;

            target.add(other);

            assertEquals(120, target.maxHostProcessingLatency);

            // Unlike the minimum, an unset maximum is harmless - Math.max ignores it.
            VideoStats withEmpty = populated();
            withEmpty.add(new VideoStats());
            assertEquals(90, withEmpty.maxHostProcessingLatency);
        }

        @Test
        @DisplayName("takes the higher of each worst-case figure")
        void takesHigherWorstCase() {
            VideoStats target = populated();
            VideoStats other = populated();
            other.worstRecvToEnqueueUs = 9000;
            other.worstDecoderTimeUs = 100;
            other.worstPresentationGapNanos = 90000000;

            target.add(other);

            assertEquals(9000, target.worstRecvToEnqueueUs);
            // The lower incoming value must not pull the running worst down
            assertEquals(7300, target.worstDecoderTimeUs);
            assertEquals(90000000, target.worstPresentationGapNanos);
        }

        @Test
        @DisplayName("sums presentation gap events rather than taking the larger count")
        void sumsPresentationGapEvents() {
            VideoStats target = populated();

            target.add(populated());

            // A count, not a worst - two windows of four gaps are eight gaps
            assertEquals(8, target.presentationGapCount);
        }

        @Test
        @DisplayName("an empty window cannot collapse a worst-case figure to zero")
        void emptyWindowDoesNotCollapseWorstCase() {
            // The failure mode this guards is the one the host-latency minimum actually had:
            // Math.min(x, 0) pinned the session minimum as soon as any window carried no data.
            // Maxima are safe because zero is their identity, and these must stay maxima.
            VideoStats target = populated();

            target.add(new VideoStats());

            assertEquals(4200, target.worstRecvToEnqueueUs);
            assertEquals(7300, target.worstDecoderTimeUs);
            assertEquals(51000000, target.worstPresentationGapNanos);
        }

        @Test
        @DisplayName("keeps the earliest start timestamp")
        void keepsEarliestStartTimestamp() {
            VideoStats target = populated();
            VideoStats later = populated();
            later.measurementStartTimestamp = 5000;

            target.add(later);

            // The merged window began when the first of the two did; this is what makes the
            // elapsed time in getFps() span both windows rather than just the newer one.
            assertEquals(1000, target.measurementStartTimestamp);
        }

        @Test
        @DisplayName("adopts the other start timestamp when this window has none")
        void adoptsStartTimestampWhenUnset() {
            VideoStats target = new VideoStats();

            target.add(populated());

            assertEquals(1000, target.measurementStartTimestamp);
        }

        @Test
        @DisplayName("is how the overlay's two-window view is built")
        void summingTwoWindowsMatchesOverlayUsage() {
            // Mirrors MediaCodecDecoderRenderer.submitDecodeUnit(): a fresh window accumulates the
            // last completed window and the active one, then reports the pair.
            VideoStats lastWindow = populated();
            VideoStats activeWindow = populated();
            activeWindow.measurementStartTimestamp = 2000;

            VideoStats lastTwo = new VideoStats();
            lastTwo.add(lastWindow);
            lastTwo.add(activeWindow);

            assertEquals(120, lastTwo.totalFrames);
            assertEquals(1000, lastTwo.measurementStartTimestamp);
        }
    }

    @Nested
    @DisplayName("copy()")
    class Copy {

        @Test
        @DisplayName("reproduces every field including the start timestamp")
        void reproducesEveryField() {
            VideoStats source = populated();
            VideoStats target = new VideoStats();

            target.copy(source);

            assertEquals(source.decoderTimeMs, target.decoderTimeMs);
            assertEquals(source.totalTimeMs, target.totalTimeMs);
            assertEquals(source.totalFrames, target.totalFrames);
            assertEquals(source.totalFramesReceived, target.totalFramesReceived);
            assertEquals(source.totalFramesRendered, target.totalFramesRendered);
            assertEquals(source.frameLossEvents, target.frameLossEvents);
            assertEquals(source.framesLost, target.framesLost);
            assertEquals(source.minHostProcessingLatency, target.minHostProcessingLatency);
            assertEquals(source.maxHostProcessingLatency, target.maxHostProcessingLatency);
            assertEquals(source.totalHostProcessingLatency, target.totalHostProcessingLatency);
            assertEquals(source.framesWithHostProcessingLatency, target.framesWithHostProcessingLatency);
            assertEquals(source.worstRecvToEnqueueUs, target.worstRecvToEnqueueUs);
            assertEquals(source.worstDecoderTimeUs, target.worstDecoderTimeUs);
            assertEquals(source.presentationGapCount, target.presentationGapCount);
            assertEquals(source.worstPresentationGapNanos, target.worstPresentationGapNanos);
            // copy() takes the timestamp verbatim where add() would have kept the earlier one
            assertEquals(source.measurementStartTimestamp, target.measurementStartTimestamp);
        }

        @Test
        @DisplayName("overwrites rather than accumulates")
        void overwritesRatherThanAccumulates() {
            VideoStats target = populated();
            VideoStats empty = new VideoStats();

            target.copy(empty);

            assertEquals(0, target.totalFrames);
            assertEquals(0, target.decoderTimeMs);
            assertEquals(0, target.measurementStartTimestamp);
        }

        @Test
        @DisplayName("leaves the source untouched")
        void leavesSourceUntouched() {
            VideoStats source = populated();
            VideoStats target = new VideoStats();

            target.copy(source);
            target.totalFrames = 999;

            assertNotSame(source, target);
            assertEquals(60, source.totalFrames);
        }
    }

    @Nested
    @DisplayName("clear()")
    class Clear {

        @Test
        @DisplayName("zeroes every counter")
        void zeroesEveryCounter() {
            VideoStats stats = populated();

            stats.clear();

            assertEquals(0, stats.decoderTimeMs);
            assertEquals(0, stats.totalTimeMs);
            assertEquals(0, stats.totalFrames);
            assertEquals(0, stats.totalFramesReceived);
            assertEquals(0, stats.totalFramesRendered);
            assertEquals(0, stats.frameLossEvents);
            assertEquals(0, stats.framesLost);
            assertEquals(0, stats.minHostProcessingLatency);
            assertEquals(0, stats.maxHostProcessingLatency);
            assertEquals(0, stats.totalHostProcessingLatency);
            assertEquals(0, stats.framesWithHostProcessingLatency);
            assertEquals(0, stats.worstRecvToEnqueueUs);
            assertEquals(0, stats.worstDecoderTimeUs);
            assertEquals(0, stats.presentationGapCount);
            assertEquals(0, stats.worstPresentationGapNanos);
            assertEquals(0, stats.measurementStartTimestamp);
        }

        @Test
        @DisplayName("lets a hitch age out instead of pinning the overlay for the whole stream")
        void worstCaseDoesNotPersistAcrossWindows() {
            // This is why the presentation gap counters moved here from the renderer, where
            // nothing ever reset them: one hitch used to hold the overlay's worst-gap figure for
            // the rest of the session, long after it stopped describing anything current.
            VideoStats window = populated();
            window.worstPresentationGapNanos = 190000000;
            window.worstRecvToEnqueueUs = 200000;

            window.clear();
            window.totalFramesReceived = 60;

            assertEquals(0, window.worstPresentationGapNanos);
            assertEquals(0, window.worstRecvToEnqueueUs);
            assertEquals(0, window.presentationGapCount);
        }

        @Test
        @DisplayName("leaves the window ready to be treated as unset by add()")
        void leavesWindowUnsetForAdd() {
            // The renderer clears the active window and immediately reuses it, so a cleared window
            // must behave like a brand new one when merged.
            VideoStats reused = populated();
            reused.clear();

            VideoStats target = new VideoStats();
            target.add(reused);
            target.add(populated());

            assertEquals(40, target.minHostProcessingLatency);
            assertEquals(1000, target.measurementStartTimestamp);
        }
    }

    @Nested
    @DisplayName("getFps()")
    class GetFps {

        @Test
        @DisplayName("divides each frame count by the elapsed window")
        void dividesByElapsedWindow() {
            VideoStats stats = populated();
            stats.measurementStartTimestamp = 1000;

            VideoStatsFps fps = stats.getFps(3000);

            // 2 seconds elapsed
            assertEquals(30.0f, fps.totalFps, 0.001f);
            assertEquals(29.0f, fps.receivedFps, 0.001f);
            assertEquals(28.5f, fps.renderedFps, 0.001f);
        }

        @Test
        @DisplayName("reports zero before any time has elapsed")
        void reportsZeroBeforeTimeElapses() {
            // Guards the divide: the window is created and read in the same millisecond during
            // startup, and the overlay must show 0 rather than infinity.
            VideoStats stats = populated();
            stats.measurementStartTimestamp = 1000;

            VideoStatsFps fps = stats.getFps(1000);

            assertEquals(0.0f, fps.totalFps, 0.001f);
            assertEquals(0.0f, fps.receivedFps, 0.001f);
            assertEquals(0.0f, fps.renderedFps, 0.001f);
        }

        @Test
        @DisplayName("reports zero rather than a negative rate if the clock runs backwards")
        void reportsZeroOnBackwardsClock() {
            VideoStats stats = populated();
            stats.measurementStartTimestamp = 5000;

            VideoStatsFps fps = stats.getFps(1000);

            assertEquals(0.0f, fps.totalFps, 0.001f);
        }

        @Test
        @DisplayName("keeps the three rates independent")
        void keepsTheThreeRatesIndependent() {
            // The gaps between the three are diagnostic: total above received means network loss,
            // received above rendered means the device cannot keep up. A test that used equal
            // counts would not catch them being wired to the same field.
            VideoStats stats = new VideoStats();
            stats.measurementStartTimestamp = 0;
            stats.totalFrames = 60;
            stats.totalFramesReceived = 50;
            stats.totalFramesRendered = 40;

            VideoStatsFps fps = stats.getFps(1000);

            assertEquals(60.0f, fps.totalFps, 0.001f);
            assertEquals(50.0f, fps.receivedFps, 0.001f);
            assertEquals(40.0f, fps.renderedFps, 0.001f);
        }
    }
}
