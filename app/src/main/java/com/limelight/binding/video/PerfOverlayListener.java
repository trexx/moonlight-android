package com.limelight.binding.video;

/**
 * Bridge between the decoder's statistics and the on-screen performance overlay.
 *
 * <p>Implemented by {@code Game}. The decoder asks {@link #isPerfOverlayVisible()} before building
 * the text, so that formatting work is skipped entirely when the overlay is hidden — which it
 * normally is, on the frame path.
 */
public interface PerfOverlayListener {
    /**
     * Delivers a fully formatted overlay update, roughly once per second.
     *
     * @param text pre-formatted, multi-line and ready to display. Called from a decoder thread,
     *             so implementations must hop to the UI thread before touching views.
     */
    void onPerfUpdate(final String text);

    /** @return true if the overlay is currently displayed */
    boolean isPerfOverlayVisible();
}
