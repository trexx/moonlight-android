package com.limelight.binding.input.capture;

import android.view.MotionEvent;

/**
 * Mouse capture strategy for the streaming activity.
 *
 * <p>Capture is what turns the local pointer into raw relative motion: without it Android moves a
 * local cursor and clamps it at the screen edges, so the host cursor stops moving as soon as the
 * local one hits a border. It also hides the local cursor, which would otherwise be drawn on top
 * of the host's.
 *
 * <p>This base class is the no-capture fallback — it accepts the calls and reports that it has no
 * relative axes to offer, so callers can use absolute positions instead of special-casing the
 * absence of a provider.
 *
 * @see InputCaptureManager for how the provider is chosen
 */
public abstract class InputCaptureProvider {
    protected boolean isCapturing;
    protected boolean isCursorVisible;

    /** Begins capturing the pointer and hides the local cursor. */
    public void enableCapture() {
        isCapturing = true;
        hideCursor();
    }

    /** Stops capturing and restores the local cursor. */
    public void disableCapture() {
        isCapturing = false;
        showCursor();
    }

    /** Releases any resources held by the provider. Called when the stream ends. */
    public void destroy() {}

    /** @return true if the pointer is currently captured */
    public boolean isCapturingActive() {
        return isCapturing;
    }

    /** Makes the local cursor visible again. */
    public void showCursor() {
        isCursorVisible = true;
    }

    /** Hides the local cursor so it isn't drawn over the host's. */
    public void hideCursor() {
        isCursorVisible = false;
    }

    /**
     * @return true if this event carries relative motion. When false the caller has to derive
     *         movement from absolute positions, which is subject to edge clamping.
     */
    public boolean eventHasRelativeMouseAxes(MotionEvent event) {
        return false;
    }

    /** @return relative X movement from the event; only meaningful if {@link #eventHasRelativeMouseAxes} is true */
    public float getRelativeAxisX(MotionEvent event) {
        return 0;
    }

    /** @return relative Y movement from the event; only meaningful if {@link #eventHasRelativeMouseAxes} is true */
    public float getRelativeAxisY(MotionEvent event) {
        return 0;
    }

    /**
     * Notifies the provider of a window focus change. Capture is lost when the window loses focus
     * and has to be re-established when it returns.
     */
    public void onWindowFocusChanged(boolean focusActive) {}
}
