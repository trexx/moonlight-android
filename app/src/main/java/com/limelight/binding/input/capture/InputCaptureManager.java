package com.limelight.binding.input.capture;

import android.app.Activity;

import com.limelight.R;

/**
 * Chooses the mouse capture implementation for this device.
 *
 * <p>Historically this picked between several strategies (SHIELD-specific capture, pointer icon
 * tricks, evdev) depending on the platform. With minSdk 30 the native pointer capture API is
 * always available, so the choice is now trivial — the indirection is kept because it is the one
 * place that would change if another strategy is ever needed again.
 */
public class InputCaptureManager {
    /**
     * @param activity the streaming activity, whose surface view receives captured pointer events
     * @return a capture provider for this device; never null
     */
    public static InputCaptureProvider getInputCaptureProvider(Activity activity) {
        // Native pointer capture is available on every supported Android version.
        return new AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.surfaceView));
    }
}
