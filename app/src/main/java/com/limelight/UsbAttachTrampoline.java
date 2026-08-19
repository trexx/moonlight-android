package com.limelight;

import android.app.Activity;
import android.os.Bundle;

/**
 * Does nothing, and exists so that Android can offer to remember USB permission.
 *
 * <p>Permission for a USB device is granted per connection unless the user ticks "use by default
 * for this USB device", and Android only offers that checkbox when the app declares an
 * {@code ACTION_USB_DEVICE_ATTACHED} intent-filter matching the device. Without one, permission is
 * asked for again on every enumeration — measured here as a dialog on each of six replugs.
 *
 * <p>That is only an annoyance while the user is the one pulling the cable. It is fatal to
 * re-enumerating a pad deliberately, which is the one recovery that clears the audio state a killed
 * process leaves behind (see {@code jni/xow_driver/AUDIO.md}): a modal dialog over a running stream,
 * every time, is worse than the fault.
 *
 * <p>The intent-filter has to live on an activity, and Android <em>launches</em> that activity when
 * a matching device is attached. So this one must not be a screen. It finishes immediately, and is
 * declared with its own task affinity and {@code Theme.NoDisplay} so it cannot join or disturb the
 * {@link Game} task — a pad re-enumerating mid-stream must not pull the user out of the game.
 *
 * <p>Nothing claims the device here. {@link com.limelight.binding.input.driver.UsbDriverService}
 * already learns about attachments through its own broadcast receiver, and it is only bound while a
 * stream is running; duplicating that here would claim pads outside a stream, which is not this
 * app's business.
 */
public class UsbAttachTrampoline extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The attachment itself is what mattered; Android has already made the permission decision
        // by the time this runs.
        finish();
    }
}
