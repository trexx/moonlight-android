package com.limelight.profiling;

import java.util.Locale;

/**
 * A fixed-size latency histogram in microseconds, for per-frame percentiles.
 *
 * <p>Why this exists: the session figures reported as {@code Average end-to-end client latency}
 * and {@code Average hardware decoder latency} are means over a whole stream, and a mean cannot
 * distinguish "every frame took 5 ms" from "most took 2 ms and a few took 60". Worse, both were
 * accumulated at millisecond resolution — {@code (enqueueTimeUs - receiveTimeUs) / 1000} truncates
 * every frame toward zero, so a frame that really took 0.9 ms contributed nothing at all. Four
 * nominally identical 90-second runs on the same build reported end-to-end means of 3, 6, 4 and
 * 4 ms and decoder means of 1, 4, 2 and 2 ms — a spread wide enough to swallow any change worth
 * making, from a metric quantised so coarsely that a quarter of its range is the rounding. The
 * percentiles those same runs produced are in HARDWARE_TESTING.md section 26. Microsecond
 * resolution and percentiles are the fix.
 *
 * <p>Buckets are logarithmic with {@value #SUB_BUCKET_BITS} bits of mantissa, the same scheme
 * HdrHistogram uses: the relative error is bounded at about 1/{@value #SUB_BUCKET_COUNT}, so a
 * 3 ms sample and a 5 ms one are never confused, while the tail out to seconds still fits in a
 * couple of kilobytes. Recording is a leading-zero count, a shift and an increment — no division,
 * no allocation, no branch on magnitude beyond the small-value case.
 *
 * <p><b>Debug builds only.</b> Every instance is constructed behind {@code BuildConfig.DEBUG} or a
 * {@link ProfilingCategory} constant, so R8 removes this class from release entirely rather than
 * merely leaving it unreached - the rule this package exists to follow. Keep it that way: a
 * caller that allocates one unconditionally would drag the whole class back into the shipped
 * binary.
 *
 * <p>Session-scoped by design, not per-window. A percentile needs a population, and the decoder's
 * one-second statistics windows hold only about sixty frames each - far too few for a p99 to mean
 * anything. Instances therefore live as long as the stream and are read once at teardown; anything
 * wanting a per-window view should read the counters in {@code VideoStats} instead.
 *
 * <p>Not synchronised, deliberately, matching {@code VideoStats}: samples are recorded on the
 * decoder threads and read once at teardown. A torn count costs one sample from a population of
 * tens of thousands.
 */
public final class LatencyHistogram {

    /** Mantissa bits kept below the leading one bit. 3 gives ~12% worst-case bucket width. */
    private static final int SUB_BUCKET_BITS = 3;
    private static final int SUB_BUCKET_COUNT = 1 << SUB_BUCKET_BITS;

    /**
     * Enough for any value a 64-bit microsecond count can hold. At ~2 KB the array is cheap
     * enough that sizing it to the expected range would be a false economy.
     */
    private static final int BUCKET_COUNT = 64 * SUB_BUCKET_COUNT;

    private final int[] counts = new int[BUCKET_COUNT];
    private final String label;
    private long count;
    private long maxUs;
    private long sumUs;
    private boolean frozen;

    public LatencyHistogram(String label) {
        this.label = label;
    }

    /**
     * Index for {@code value}: below {@link #SUB_BUCKET_COUNT} the histogram is exact, above it
     * the value is split into an exponent and the top {@value #SUB_BUCKET_BITS} mantissa bits.
     */
    private static int bucketFor(long value) {
        if (value < SUB_BUCKET_COUNT) {
            return (int) value;
        }

        int exponent = 63 - Long.numberOfLeadingZeros(value);
        int shift = exponent - SUB_BUCKET_BITS;
        int sub = (int) ((value >>> shift) & (SUB_BUCKET_COUNT - 1));
        return ((shift + 1) << SUB_BUCKET_BITS) + sub;
    }

    /** Inclusive lower bound of the values that land in {@code bucket}. */
    private static long lowerBound(int bucket) {
        if (bucket < SUB_BUCKET_COUNT) {
            return bucket;
        }

        int shift = (bucket >>> SUB_BUCKET_BITS) - 1;
        long sub = bucket & (SUB_BUCKET_COUNT - 1);
        return (SUB_BUCKET_COUNT + sub) << shift;
    }

    /** Exclusive upper bound, which is what percentiles are reported as. */
    private static long upperBound(int bucket) {
        return lowerBound(bucket + 1);
    }

    /** Records one sample. Negative values are dropped: callers use -1 for "no sample". */
    public void record(long microseconds) {
        if (frozen || microseconds < 0) {
            return;
        }

        counts[bucketFor(microseconds)]++;
        count++;
        sumUs += microseconds;
        if (microseconds > maxUs) {
            maxUs = microseconds;
        }
    }

    /**
     * Stops recording, leaving what has been collected readable.
     *
     * <p>Called when the game menu opens. Opening the menu stalls the decoder for roughly 190 ms
     * and the frames queued behind it then drain as a burst of large samples, which is pure
     * teardown artefact - it happens after the user has decided to stop streaming. Left
     * unfrozen it lands entirely in p99.9 and max and makes both meaningless; see
     * HARDWARE_TESTING.md section 26.
     *
     * <p>Set from the UI thread and read on the decoder threads without synchronisation, matching
     * the rest of this class. The worst case is a handful of extra samples recorded before the
     * write is observed, which is orders of magnitude better than recording the whole burst.
     */
    public void freeze() {
        frozen = true;
    }

    public long getCount() {
        return count;
    }

    /**
     * @param fraction e.g. 0.99 for p99
     * @return an upper bound on that percentile in microseconds, or 0 if nothing was recorded
     */
    public long percentileUs(double fraction) {
        if (count == 0) {
            return 0;
        }

        long target = (long) Math.ceil(count * fraction);
        long running = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            running += counts[i];
            if (running >= target) {
                return upperBound(i);
            }
        }

        return maxUs;
    }

    /**
     * One line for the stream summary. Percentiles are upper bounds, hence the {@code <}; the
     * mean is exact because the running sum is kept separately from the buckets, so it can be
     * compared directly against the old session average this replaces.
     *
     * <p>Formatted in {@link Locale#US} rather than the device's: this line is grepped out of
     * logcat by the measurement scripts, and a device set to a comma-decimal locale would
     * otherwise emit {@code mean=2,67ms} and break them.
     */
    public String summarise() {
        if (count == 0) {
            return label + ": no samples";
        }

        return String.format(Locale.US,
                "%s: n=%d mean=%.2fms p50<%.2f p90<%.2f p99<%.2f p99.9<%.2f max=%.2fms",
                label, count, sumUs / 1000.0 / count,
                percentileUs(0.50) / 1000.0, percentileUs(0.90) / 1000.0,
                percentileUs(0.99) / 1000.0, percentileUs(0.999) / 1000.0,
                maxUs / 1000.0);
    }
}
