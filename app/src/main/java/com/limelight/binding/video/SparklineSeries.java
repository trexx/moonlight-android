package com.limelight.binding.video;

/**
 * A fixed-length history of one overlay metric, and the scaling that turns it into plot
 * coordinates.
 *
 * <p>Why this exists: every figure on the performance overlay is a number with no time axis, so a
 * step change, a spike and a slow drift all look identical to someone watching it. That axis is
 * what actually solves problems here - the findings in HARDWARE_TESTING.md section 26 (the HDR
 * codec restart at t+0.06s, the decoder tail belonging to the disconnect menu rather than the
 * stream) were each identified by knowing <em>when</em> something happened, and none of them was
 * visible in the overlay at the time.
 *
 * <p>Deliberately free of Android imports and of {@code LimeLog}, which is backed by
 * {@code android.util.Log} and throws under the stubbed android.jar the JVM tests run against.
 * {@link com.limelight.ui.SparklineView} owns everything that needs a {@code Canvas}; this owns the
 * arithmetic, which is the part worth pinning down off-device.
 *
 * <p>Not in {@code com.limelight.profiling}: that package is debug-only by rule, and the frame rate
 * and network latency plots ship in release.
 *
 * <p>Latency: none. {@link #push} is called once per measurement window - about once a second, on
 * the overlay handler - and the rest is called from {@code onDraw}. Nothing here is on the frame
 * path, and nothing allocates after construction.
 *
 * <p>Not synchronised, matching {@link VideoStats}: written on the overlay handler and read on the
 * UI thread when the view draws. A torn read costs one odd-looking sample for one second.
 */
public final class SparklineSeries {

    /**
     * How many samples the plot holds. At one per measurement window that is just over a minute of
     * history - long enough to show a drift, short enough that the current second is still a
     * readable fraction of the width on a TV.
     *
     * <p>64 rather than 60 so it is already a power of two. The constructor rounds up, so asking
     * for 60 would quietly give 64 anyway and leave this constant describing something the class
     * does not do.
     */
    public static final int SAMPLE_COUNT = 64;

    /**
     * Smallest full-scale span an auto-scaled plot may use, in the series' own units.
     *
     * <p>Without a floor, autoscaling amplifies noise into alarm: a network sitting between 3.1 and
     * 3.4 ms fills the whole plot height with what is actually a flat line. The floor means a quiet
     * metric draws as the flat line it is.
     */
    public static final float MIN_AUTO_SPAN = 10f;

    private final float[] samples;
    private final int mask;
    private final int capacity;

    /** Index one past the newest sample, so the oldest is at {@code head - count}. */
    private int head;
    private int count;

    public SparklineSeries() {
        this(SAMPLE_COUNT);
    }

    /**
     * @param requestedCapacity samples retained before the oldest is dropped. Rounded up to a power
     *                          of two so the wrap is a mask rather than a division, as
     *                          {@link OutputBufferRing} does. Tests use small values here rather
     *                          than pushing sixty samples to reach an edge.
     */
    public SparklineSeries(int requestedCapacity) {
        int cap = Integer.highestOneBit(Math.max(1, requestedCapacity));
        if (cap < requestedCapacity) {
            cap <<= 1;
        }

        this.capacity = cap;
        this.mask = cap - 1;
        this.samples = new float[cap];
    }

    /**
     * Appends a sample, dropping the oldest once full.
     *
     * <p>Non-finite values are dropped rather than stored. They arrive as {@code 0/0} whenever a
     * rate is derived from a window in which nothing was received - a stall, or the first window of
     * a stream - and a single NaN would otherwise propagate through {@link #min} and {@link #max}
     * and blank the whole plot, exactly when it is being looked at.
     */
    public void push(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return;
        }

        samples[head & mask] = value;
        head++;

        if (count < capacity) {
            count++;
        }
    }

    /** @return how many samples are held, which is below the capacity until the ring first fills */
    public int size() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    /**
     * @param index 0 for the oldest sample held, {@code size() - 1} for the newest
     * @throws IndexOutOfBoundsException if there is no such sample
     */
    public float valueAt(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("no sample " + index + " of " + count);
        }

        return samples[(head - count + index) & mask];
    }

    /** @return the newest sample, or 0 if none has been pushed */
    public float latest() {
        return count == 0 ? 0 : valueAt(count - 1);
    }

    /** @return the smallest sample held, or 0 if none has been pushed */
    public float min() {
        if (count == 0) {
            return 0;
        }

        float lowest = valueAt(0);
        for (int i = 1; i < count; i++) {
            lowest = Math.min(lowest, valueAt(i));
        }
        return lowest;
    }

    /** @return the largest sample held, or 0 if none has been pushed */
    public float max() {
        if (count == 0) {
            return 0;
        }

        float highest = valueAt(0);
        for (int i = 1; i < count; i++) {
            highest = Math.max(highest, valueAt(i));
        }
        return highest;
    }

    /**
     * Full-scale value for a plot that scales itself to what it holds, rounded up away from
     * {@link #MIN_AUTO_SPAN}.
     *
     * <p>Zero-based rather than spanning min to max, so the height of the line means the size of
     * the value. A min-to-max axis makes 3.1 ms and 3.4 ms look like a doubling.
     */
    public float autoScaleTop() {
        return Math.max(MIN_AUTO_SPAN, max());
    }

    /**
     * Maps a value onto 0..1 for plotting, where 0 is the bottom of the plot and 1 the top.
     *
     * <p>Values outside the range clamp rather than escaping the plot, so a single outlier draws as
     * a line pinned to the ceiling instead of scribbling over the rest of the overlay.
     *
     * @param top full-scale value; a non-positive top yields 0 rather than dividing by it
     */
    public static float normalise(float value, float top) {
        if (!(top > 0) || Float.isNaN(value)) {
            return 0;
        }

        if (value <= 0) {
            return 0;
        }

        return value >= top ? 1 : value / top;
    }
}
