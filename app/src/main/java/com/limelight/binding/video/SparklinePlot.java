package com.limelight.binding.video;

/**
 * One plot of the performance overlay: its label, the series drawn on it, and how its axis scales.
 *
 * <p>Several series share a plot when the gap between them is the diagnosis rather than either
 * line alone. Incoming against rendered frame rate is the case this was built for, and
 * {@link VideoStats} already describes it in prose: total above received means network loss,
 * received above rendered means the device cannot keep up. Drawn on one axis, that sentence becomes
 * something you read at a glance.
 *
 * <p>Deliberately free of Android imports and of {@code LimeLog}, which is backed by
 * {@code android.util.Log} and throws under the stubbed android.jar the JVM tests run against. The
 * label arrives pre-formatted from the caller, which is the one that holds a {@code Context} - the
 * same split {@code GameMenuLayout} uses, where the decision moves out and the strings stay put.
 *
 * <p>Latency: none. Built once per stream, updated once per measurement window, read from
 * {@code onDraw}. Nothing here is on the frame path.
 */
public final class SparklinePlot {

    /**
     * Full-scale value, or a non-positive number to scale to whatever the series hold.
     *
     * <p>The distinction decides whether a plot is useful or alarming. Frame rate takes a fixed
     * axis of 0..refreshRate: autoscaling a stream that sits between 59.9 and 60.0 fills the plot
     * with what is actually a flat line. Latency autoscales, because its interesting range spans
     * two orders of magnitude and no fixed ceiling suits both a 3 ms network and a 190 ms stall.
     */
    private final float fixedTop;

    private final SparklineSeries[] series;
    private final int[] colours;

    private String label;

    /**
     * @param fixedTop full scale, or 0 or less to autoscale
     * @param colours  one ARGB colour per series, in the order the series are given
     */
    public SparklinePlot(String label, float fixedTop, int[] colours, SparklineSeries... series) {
        if (colours.length != series.length) {
            throw new IllegalArgumentException(
                    "have " + colours.length + " colours for " + series.length + " series");
        }

        this.label = label;
        this.fixedTop = fixedTop;
        this.colours = colours;
        this.series = series;
    }

    /** Replaces the label, which carries the current values and so changes every window. */
    public void setLabel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public int seriesCount() {
        return series.length;
    }

    public SparklineSeries seriesAt(int index) {
        return series[index];
    }

    public int colourAt(int index) {
        return colours[index];
    }

    /**
     * @return the value at the top of the axis. For an autoscaled plot this is the largest sample
     *         any of its series holds, floored by {@link SparklineSeries#MIN_AUTO_SPAN} - series
     *         sharing a plot must share an axis, or the gap between two lines stops meaning
     *         anything.
     */
    public float top() {
        if (fixedTop > 0) {
            return fixedTop;
        }

        float top = SparklineSeries.MIN_AUTO_SPAN;
        for (SparklineSeries s : series) {
            top = Math.max(top, s.autoScaleTop());
        }
        return top;
    }
}
