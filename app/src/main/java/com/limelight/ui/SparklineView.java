package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.limelight.binding.video.SparklinePlot;
import com.limelight.binding.video.SparklineSeries;

import java.util.Collections;
import java.util.List;

/**
 * Draws the performance overlay's time-series plots.
 *
 * <p>The overlay's numbers have no time axis, so a step change, a spike and a slow drift all read
 * the same. That axis is what actually solves problems on this hardware - see the plots' rationale
 * on {@code SparklineSeries} and HARDWARE_TESTING.md section 26 - and it is the one thing a row of
 * text cannot express.
 *
 * <p><b>The first Canvas-drawing view in this codebase</b>, so it sets the conventions rather than
 * following them: every {@link Paint} and the {@link Path} are fields allocated once, {@code
 * onDraw} calls {@link Path#rewind()} rather than {@code reset()} to keep the internal buffer, and
 * nothing is allocated per draw. A view that allocates in {@code onDraw} is the standard way to
 * make an overlay cost more than what it measures.
 *
 * <p>Follows {@link StreamView}'s stance of state pushed in from the caller rather than queried
 * here: {@link #setPlots} hands over what to draw, and the view asks nothing of anyone.
 *
 * <p>Latency: none on any frame path. {@link #setPlots} is called once per measurement window -
 * about once a second, from the overlay handler - and each call invalidates, so this draws at 1 Hz
 * and only while the overlay is visible. The plot data itself is collected from figures the stats
 * window has already computed, so nothing here adds per-frame work.
 */
public class SparklineView extends View {

    /** Plot height, before the label row. Sized to stay legible at TV viewing distance. */
    private static final int PLOT_HEIGHT_DP = 34;
    /** Gap between stacked plots. */
    private static final int PLOT_SPACING_DP = 10;
    private static final int LABEL_SIZE_SP = 12;
    private static final int STROKE_WIDTH_DP = 2;
    /** Inset so a line pinned at full scale is not clipped by the view bounds. */
    private static final int VERTICAL_PADDING_DP = 3;

    private static final int AXIS_COLOR = 0x40FFFFFF;
    private static final int LABEL_COLOR = 0xFFFFFFFF;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();

    private final float plotHeightPx;
    private final float plotSpacingPx;
    private final float verticalPaddingPx;

    private List<SparklinePlot> plots = Collections.emptyList();

    public SparklineView(Context context) {
        this(context, null);
    }

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);

        var metrics = context.getResources().getDisplayMetrics();
        plotHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, PLOT_HEIGHT_DP, metrics);
        plotSpacingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, PLOT_SPACING_DP, metrics);
        verticalPaddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, VERTICAL_PADDING_DP, metrics);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, STROKE_WIDTH_DP, metrics));
        // Round joins so a spike reads as a spike rather than as a mitre artefact
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1);
        axisPaint.setColor(AXIS_COLOR);

        labelPaint.setColor(LABEL_COLOR);
        labelPaint.setTextSize(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, LABEL_SIZE_SP, metrics));
    }

    /**
     * Replaces what is drawn and schedules a redraw.
     *
     * <p>Called on the UI thread, once per measurement window. The list is retained rather than
     * copied: the caller builds it once at construction and mutates the series inside it, which is
     * what keeps this free of per-second allocation.
     */
    public void setPlots(List<SparklinePlot> plots) {
        this.plots = plots == null ? Collections.emptyList() : plots;
        invalidate();
    }

    /** {@inheritDoc} */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = plots.size();
        int height = count == 0 ? 0
                : (int) Math.ceil(count * (plotHeightPx + plotSpacingPx) + labelRowHeight() * count);

        setMeasuredDimension(resolveSize(0, widthMeasureSpec),
                resolveSize(height, heightMeasureSpec));
    }

    private float labelRowHeight() {
        return labelPaint.getTextSize() * 1.3f;
    }

    /** {@inheritDoc} */
    @Override
    protected void onDraw(Canvas canvas) {
        // No super.onDraw: View's is empty, and this runs with the overlay up.
        final int plotCount = plots.size();
        if (plotCount == 0) {
            return;
        }

        final float width = getWidth();
        if (width <= 0) {
            return;
        }

        final float labelHeight = labelRowHeight();
        float top = 0;

        for (int p = 0; p < plotCount; p++) {
            final SparklinePlot plot = plots.get(p);

            labelPaint.setColor(LABEL_COLOR);
            canvas.drawText(plot.label(), 0, top + labelPaint.getTextSize(), labelPaint);

            final float plotTop = top + labelHeight + verticalPaddingPx;
            final float plotBottom = top + labelHeight + plotHeightPx - verticalPaddingPx;

            // Baseline, so an empty plot still reads as a plot rather than as blank overlay
            canvas.drawLine(0, plotBottom, width, plotBottom, axisPaint);

            drawSeries(canvas, plot, plotTop, plotBottom, width);

            top += labelHeight + plotHeightPx + plotSpacingPx;
        }
    }

    /**
     * Draws every series of one plot onto a shared axis.
     *
     * <p>The x step spans the series' full capacity rather than what it currently holds, so a
     * partly filled plot grows in from the left instead of stretching two samples across the whole
     * width and implying a history that is not there.
     */
    private void drawSeries(Canvas canvas, SparklinePlot plot, float plotTop, float plotBottom,
                            float width) {
        final float top = plot.top();
        final float height = plotBottom - plotTop;
        final int seriesCount = plot.seriesCount();

        for (int s = 0; s < seriesCount; s++) {
            final var series = plot.seriesAt(s);
            final int samples = series.size();
            if (samples < 2) {
                continue;
            }

            final float xStep = width / (series.capacity() - 1);

            linePath.rewind();
            for (int i = 0; i < samples; i++) {
                final float x = i * xStep;
                final float y = plotBottom - height * SparklineSeries.normalise(series.valueAt(i), top);

                if (i == 0) {
                    linePath.moveTo(x, y);
                } else {
                    linePath.lineTo(x, y);
                }
            }

            linePaint.setColor(plot.colourAt(s));
            canvas.drawPath(linePath, linePaint);
        }
    }
}
