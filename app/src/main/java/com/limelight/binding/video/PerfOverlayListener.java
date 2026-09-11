package com.limelight.binding.video;

/**
 * Bridge between the decoder's statistics and the on-screen performance overlay.
 *
 * <p>Implemented by {@code Game}. The decoder asks {@link #isPerfOverlayVisible()} on the frame
 * path before it snapshots anything, so a hidden overlay — which is the normal case — costs
 * nothing beyond that check.
 */
public interface PerfOverlayListener {
    /**
     * Delivers a fully formatted overlay update, roughly once per second.
     *
     * <p>Called on the main looper, not the decode thread: the decoder snapshots its counters on
     * the frame path and formats the text after hopping off it. Implementations should still not
     * assume a thread beyond that.
     *
     * @param text pre-formatted, multi-line and ready to display
     */
    void onPerfUpdate(final String text);

    /**
     * Hands over the plots to draw, roughly once per second, immediately before
     * {@link #onPerfUpdate}.
     *
     * <p>Separate from the text because the plots are not text: the decoder owns the series and
     * pushes a sample into each as its window rolls, and the view redraws from them. The list is
     * the same one every time - built once per stream, with its series mutated in place - so this
     * allocates nothing per update.
     *
     * @param plots in display order, or empty to draw nothing
     */
    void onPerfPlots(final java.util.List<SparklinePlot> plots);

    /** @return true if the overlay is currently displayed */
    boolean isPerfOverlayVisible();
}
