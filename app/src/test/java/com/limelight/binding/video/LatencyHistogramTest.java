package com.limelight.binding.video;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two properties that make the percentile figures worth trusting: a reported
 * percentile is never optimistic, and it is never wrong by more than the documented bucket width.
 *
 * <p>The class it covers replaces a pair of session means that were accumulated at integer
 * millisecond resolution. If the bucketing here were subtly wrong the replacement would be no
 * better than what it replaced, and nothing about the output would look wrong — a percentile is
 * plausible whatever it says. Hence the sorted-oracle comparison: it is the only check that can
 * tell a correct histogram from a confident one.
 *
 * <p>Everything is exercised through the recording API rather than the private bucket arithmetic,
 * so the test says nothing about how the buckets are laid out and stays valid if that changes.
 */
class LatencyHistogramTest {

    /**
     * The documented worst-case bucket width: three mantissa bits, so one part in eight, plus a
     * little slack for the +1 that separates adjacent bucket bounds.
     */
    private static final double MAX_OVERSHOOT = 1.125;

    /** Reads back the only percentile that a single-sample histogram can be asked about. */
    private static long soleSampleUpperBound(long micros) {
        LatencyHistogram h = new LatencyHistogram("t");
        h.record(micros);
        return h.percentileUs(1.0);
    }

    @Nested
    @DisplayName("record()")
    class Record {

        @Test
        @DisplayName("counts every non-negative sample")
        void countsSamples() {
            LatencyHistogram h = new LatencyHistogram("t");
            for (int i = 0; i < 1000; i++) {
                h.record(i);
            }
            assertEquals(1000, h.getCount(), "every sample should have been counted");
        }

        @ParameterizedTest(name = "record({0}) is ignored")
        @ValueSource(longs = {-1, -2, Long.MIN_VALUE})
        @DisplayName("drops negative samples rather than counting them as zero")
        void dropsNegatives(long micros) {
            // Callers pass -1 for "this frame had no sample". Counting those as zero would drag
            // every percentile down and make an unmeasurable stream look like a fast one.
            LatencyHistogram h = new LatencyHistogram("t");
            h.record(micros);
            assertEquals(0, h.getCount(), "a negative sample should not be recorded");
        }

        @Test
        @DisplayName("stops recording once frozen, keeping what it already held")
        void freezeStopsRecording() {
            LatencyHistogram h = new LatencyHistogram("t");
            h.record(1000);
            h.record(2000);
            h.freeze();
            h.record(500000);

            assertEquals(2, h.getCount(), "the post-freeze sample should not have been recorded");
            assertTrue(h.percentileUs(1.0) < 500000,
                    "the 500 ms sample must not reach the maximum after a freeze");
        }
    }

    @Nested
    @DisplayName("percentileUs()")
    class Percentiles {

        @Test
        @DisplayName("returns zero for an empty histogram rather than throwing")
        void emptyIsZero() {
            LatencyHistogram h = new LatencyHistogram("t");
            assertEquals(0, h.percentileUs(0.99), "an empty histogram has no percentile");
        }

        @ParameterizedTest(name = "a sole sample of {0} us reports an upper bound above it")
        @ValueSource(longs = {0, 1, 7, 8, 15, 16, 100, 1_000, 12_345, 1_000_000, 10_000_000})
        @DisplayName("never reports a percentile below the sample it saw")
        void neverOptimistic(long micros) {
            // The figure is printed with a '<', so it is a claim that the true value is below it.
            // Reporting anything at or under the sample would make that claim false.
            assertTrue(soleSampleUpperBound(micros) > micros,
                    "reported bound " + soleSampleUpperBound(micros)
                            + " must exceed the sample " + micros);
        }

        @ParameterizedTest(name = "{0} us overshoots by no more than the bucket width")
        @ValueSource(longs = {16, 100, 1_000, 12_345, 250_000, 1_000_000, 10_000_000})
        @DisplayName("overshoots by at most one bucket width")
        void boundedOvershoot(long micros) {
            long reported = soleSampleUpperBound(micros);
            assertTrue(reported <= micros * MAX_OVERSHOOT + 1,
                    "reported bound " + reported + " overshoots " + micros + " by more than "
                            + MAX_OVERSHOOT + "x");
        }

        @Test
        @DisplayName("is monotonic: a larger sample never reports a smaller bound")
        void monotonic() {
            long previous = -1;
            for (long v = 0; v <= 10_000_000L; v = v < 1000 ? v + 1 : (long) (v * 1.01) + 1) {
                long reported = soleSampleUpperBound(v);
                assertTrue(reported >= previous,
                        "bound for " + v + " (" + reported + ") is below the bound for the "
                                + "previous, smaller sample (" + previous + ")");
                previous = reported;
            }
        }

        @Test
        @DisplayName("matches an exact sorted oracle on a realistic bimodal distribution")
        void matchesSortedOracle() {
            // The shape that motivated the class: a tight body with a sparse slow tail, which is
            // what a decoder under load actually produces and what a mean cannot describe.
            Random random = new Random(42);
            LatencyHistogram histogram = new LatencyHistogram("t");
            List<Long> exact = new ArrayList<>();

            for (int i = 0; i < 200_000; i++) {
                long micros = random.nextDouble() < 0.999
                        ? (long) (1500 + random.nextGaussian() * 400)
                        : (long) (60_000 + random.nextGaussian() * 30_000);
                if (micros < 0) {
                    micros = 0;
                }
                histogram.record(micros);
                exact.add(micros);
            }
            Collections.sort(exact);

            for (double fraction : new double[] {0.50, 0.90, 0.99, 0.999}) {
                long reported = histogram.percentileUs(fraction);
                long truth = exact.get((int) Math.ceil(exact.size() * fraction) - 1);

                assertTrue(reported >= truth,
                        "p" + (fraction * 100) + " reported " + reported
                                + " below the true value " + truth);
                assertTrue(reported <= truth * MAX_OVERSHOOT + 1,
                        "p" + (fraction * 100) + " reported " + reported
                                + " more than a bucket above the true value " + truth);
            }
        }

        @Test
        @DisplayName("separates a fast body from a slow tail, which a mean cannot")
        void tailIsVisible() {
            // The whole reason the class exists. A mean over this data reads as healthy while one
            // frame in a hundred takes forty times as long.
            LatencyHistogram histogram = new LatencyHistogram("t");
            for (int i = 0; i < 9900; i++) {
                histogram.record(1_000);
            }
            for (int i = 0; i < 100; i++) {
                histogram.record(40_000);
            }

            assertTrue(histogram.percentileUs(0.50) < 2_000,
                    "the body should sit near 1 ms at the median");
            assertTrue(histogram.percentileUs(0.999) >= 40_000,
                    "the 40 ms tail should be visible at p99.9");
        }
    }

    @Nested
    @DisplayName("summarise()")
    class Summarise {

        @Test
        @DisplayName("says so plainly when nothing was recorded")
        void emptyReadsAsNoSamples() {
            assertEquals("Decoder: no samples", new LatencyHistogram("Decoder").summarise(),
                    "an unrecorded stage must be distinguishable from a fast one");
        }

        @ParameterizedTest(name = "mean of {0} and {1} us reports as {2}")
        @CsvSource({
                "1000, 3000, mean=2.00ms",
                "1000, 1000, mean=1.00ms",
                "500,  1500, mean=1.00ms",
        })
        @DisplayName("reports the mean exactly, not from the buckets")
        void meanIsExact(long first, long second, String expected) {
            // Kept as a running sum alongside the buckets specifically so it can be compared
            // against the session average it replaces without bucketing error in the way.
            LatencyHistogram histogram = new LatencyHistogram("t");
            histogram.record(first);
            histogram.record(second);

            assertTrue(histogram.summarise().contains(expected),
                    "expected " + expected + " in: " + histogram.summarise());
        }

        @Test
        @DisplayName("formats in a fixed locale so the measurement scripts can grep it")
        void usesFixedLocale() {
            Locale original = Locale.getDefault();
            try {
                // A comma-decimal locale would otherwise emit mean=2,00ms.
                Locale.setDefault(Locale.GERMANY);

                LatencyHistogram histogram = new LatencyHistogram("t");
                histogram.record(2000);

                String summary = histogram.summarise();
                assertTrue(summary.contains("mean=2.00ms"),
                        "expected a dot decimal separator in: " + summary);
                assertFalse(summary.contains(","),
                        "no comma should appear in: " + summary);
            } finally {
                Locale.setDefault(original);
            }
        }
    }
}
