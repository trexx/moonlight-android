package com.limelight.binding.input.capture;

import android.app.Activity;
import android.content.Context;
import android.view.PointerIcon;
import android.view.View;

/**
 * Hides the local cursor by giving the stream view a null pointer icon.
 *
 * <p>Not true capture — the pointer is still clamped to the screen — but it stops the local cursor
 * being drawn on top of the host's. Exists as a base class for
 * {@link AndroidNativePointerCaptureProvider}, which inherits this behaviour for the cases where
 * real capture is unavailable (DeX, ChromeOS).
 */
public class AndroidPointerIconCaptureProvider extends InputCaptureProvider {
    private final View targetView;
    private final Context context;

    /** @param targetView the view whose pointer icon is suppressed */
    public AndroidPointerIconCaptureProvider(Activity activity, View targetView) {
        this.context = activity;
        this.targetView = targetView;
    }

    /** {@inheritDoc} Sets a null pointer icon so no cursor is drawn over the stream. */
    @Override
    public void hideCursor() {
        super.hideCursor();
        targetView.setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL));
    }

    /** {@inheritDoc} Restores the default pointer icon. */
    @Override
    public void showCursor() {
        super.showCursor();
        targetView.setPointerIcon(null);
    }
}
