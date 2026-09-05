package com.limelight.binding.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the overlay's plot history and its axis scaling.
 *
 * <p>These guard the two ways a sparkline stops being informative without failing: a ring that
 * loses the ordering of what it holds, so the shape on screen is not the shape that happened; and
 * an axis that amplifies a flat line into a mountain range, so a healthy stream looks alarming.
 * Neither shows up as a crash, and neither is visible without a device unless it is pinned here.
 */
class SparklineSeriesTest {

    /** A four-sample ring, so the wrap is two pushes away rather than sixty. */
    private static SparklineSeries small() {
        return new SparklineSeries(4);
    }

    @Nested
    @DisplayName("push() and ordering")
    class Ordering {

        @Test
        @DisplayName("reads back oldest-first, which is the order it is drawn in")
        void readsOldestFirst() {
            SparklineSeries series = small();
            series.push(1);
            series.push(2);
            series.push(3);

            assertEquals(3, series.size());
            assertEquals(1, series.valueAt(0), 0.001f);
            assertEquals(2, series.valueAt(1), 0.001f);
            assertEquals(3, series.valueAt(2), 0.001f);
        }

        @Test
        @DisplayName("drops the oldest sample once full, keeping the rest in order")
        void dropsOldestWhenFull() {
            SparklineSeries series = small();
            for (int i = 1; i <= 6; i++) {
                series.push(i);
            }

            // Capacity 4, so 1 and 2 are gone and 3..6 remain in order
            assertEquals(4, series.size());
            assertEquals(3, series.valueAt(0), 0.001f);
            assertEquals(4, series.valueAt(1), 0.001f);
            assertEquals(5, series.valueAt(2), 0.001f);
            assertEquals(6, series.valueAt(3), 0.001f);
        }

        @Test
        @DisplayName("survives many wraps rather than drifting by one each time")
        void survivesManyWraps() {
            SparklineSeries series = small();
            for (int i = 0; i < 1000; i++) {
                series.push(i);
            }

            assertEquals(996, series.valueAt(0), 0.001f);
            assertEquals(999, series.valueAt(3), 0.001f);
            assertEquals(999, series.latest(), 0.001f);
        }

        @Test
        @DisplayName("rounds a non-power-of-two capacity up rather than down")
        void roundsCapacityUp() {
            // Rounding down would silently hold less history than asked for
            assertEquals(8, new SparklineSeries(5).capacity());
            assertEquals(8, new SparklineSeries(8).capacity());
            assertEquals(16, new SparklineSeries(9).capacity());
        }

        @Test
        @DisplayName("holds about a minute of history by default, with no rounding surprise")
        void defaultHoldsAMinute() {
            // Pinned because the plot's readability depends on it, and because every other test
            // here deliberately uses a small ring instead. SAMPLE_COUNT is already a power of two
            // so that it survives the constructor unchanged - a value of 60 would silently become
            // 64 and leave the constant describing something the class does not do.
            assertEquals(SparklineSeries.SAMPLE_COUNT, new SparklineSeries().capacity());
            assertEquals(64, SparklineSeries.SAMPLE_COUNT);
        }

        @Test
        @DisplayName("refuses an index outside what it holds")
        void rejectsBadIndex() {
            SparklineSeries series = small();
            series.push(1);

            assertThrows(IndexOutOfBoundsException.class, () -> series.valueAt(1));
            assertThrows(IndexOutOfBoundsException.class, () -> series.valueAt(-1));
        }
    }

    @Nested
    @DisplayName("non-finite samples")
    class NonFinite {

        @Test
        @DisplayName("drops NaN rather than letting it blank the whole plot")
        void dropsNaN() {
            // A rate derived from a window that received nothing is 0/0. Stored, it would poison
            // min() and max() and blank the plot during precisely the stall being looked at.
            SparklineSeries series = small();
            series.push(10);
            series.push(Float.NaN);
            series.push(20);

            assertEquals(2, series.size());
            assertEquals(10, series.valueAt(0), 0.001f);
            assertEquals(20, series.valueAt(1), 0.001f);
            assertEquals(20, series.max(), 0.001f);
        }

        @Test
        @DisplayName("drops infinities too")
        void dropsInfinities() {
            SparklineSeries series = small();
            series.push(Float.POSITIVE_INFINITY);
            series.push(Float.NEGATIVE_INFINITY);

            assertEquals(0, series.size());
        }
    }

    @Nested
    @DisplayName("empty series")
    class Empty {

        @Test
        @DisplayName("reports zeroes instead of throwing, since the first window draws before data")
        void emptyIsSafe() {
            SparklineSeries series = small();

            assertEquals(0, series.size());
            assertEquals(0, series.min(), 0.001f);
            assertEquals(0, series.max(), 0.001f);
            assertEquals(0, series.latest(), 0.001f);
            assertEquals(SparklineSeries.MIN_AUTO_SPAN, series.autoScaleTop(), 0.001f);
        }
    }

    @Nested
    @DisplayName("autoScaleTop()")
    class AutoScale {

        @Test
        @DisplayName("does not amplify a flat line into a mountain range")
        void flatLineStaysFlat() {
            // The failure this guards: a network sitting at 3.1-3.4 ms is a flat line, but a
            // min-to-max axis draws it filling the plot and reads as wild instability.
            SparklineSeries series = small();
            series.push(3.1f);
            series.push(3.4f);
            series.push(3.2f);

            float top = series.autoScaleTop();
            assertEquals(SparklineSeries.MIN_AUTO_SPAN, top, 0.001f);

            // All three land in the bottom third of the plot, close together
            float lowest = SparklineSeries.normalise(3.1f, top);
            float highest = SparklineSeries.normalise(3.4f, top);
            assertTrue(highest - lowest < 0.05f,
                    "a 0.3 ms spread should not span the plot, but covered " + (highest - lowest));
            assertTrue(highest < 0.5f, "a quiet metric should sit low in the plot");
        }

        @Test
        @DisplayName("grows past the floor once a real outlier arrives")
        void growsForOutliers() {
            SparklineSeries series = small();
            series.push(3);
            series.push(190);

            assertEquals(190, series.autoScaleTop(), 0.001f);
            assertEquals(1.0f, SparklineSeries.normalise(190, series.autoScaleTop()), 0.001f);
        }

        @Test
        @DisplayName("is zero-based, so line height means value size")
        void isZeroBased() {
            SparklineSeries series = small();
            series.push(100);
            series.push(200);

            // Half the top value draws at half height. A min-to-max axis would put 100 at the
            // floor and make it look like nothing.
            assertEquals(0.5f, SparklineSeries.normalise(100, series.autoScaleTop()), 0.001f);
        }
    }

    @Nested
    @DisplayName("normalise()")
    class Normalise {

        @Test
        @DisplayName("maps a fixed axis proportionally")
        void mapsFixedAxis() {
            // The frame rate plot uses a fixed 0..refreshRate axis rather than autoscaling, or a
            // stream holding 59.9-60.0 fills the plot with meaningless noise.
            assertEquals(0.0f, SparklineSeries.normalise(0, 60), 0.001f);
            assertEquals(0.5f, SparklineSeries.normalise(30, 60), 0.001f);
            assertEquals(0.998f, SparklineSeries.normalise(59.9f, 60), 0.001f);
            assertEquals(1.0f, SparklineSeries.normalise(60, 60), 0.001f);
        }

        @Test
        @DisplayName("clamps rather than letting an outlier draw outside the plot")
        void clampsOutOfRange() {
            assertEquals(1.0f, SparklineSeries.normalise(500, 60), 0.001f);
            assertEquals(0.0f, SparklineSeries.normalise(-5, 60), 0.001f);
        }

        @Test
        @DisplayName("yields zero for a non-positive top instead of dividing by it")
        void handlesZeroTop() {
            // refreshRate is 0 until setup() runs, and the overlay can draw before then
            assertEquals(0.0f, SparklineSeries.normalise(30, 0), 0.001f);
            assertEquals(0.0f, SparklineSeries.normalise(30, -1), 0.001f);
        }

        @Test
        @DisplayName("yields zero for NaN rather than propagating it into a coordinate")
        void handlesNaN() {
            assertEquals(0.0f, SparklineSeries.normalise(Float.NaN, 60), 0.001f);
        }
    }
}
