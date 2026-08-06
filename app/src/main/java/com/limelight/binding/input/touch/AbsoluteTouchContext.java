package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * Direct-touch mouse mode: where you touch is where the host cursor goes.
 *
 * <p>The gesture vocabulary is the one users expect from a touchscreen rather than from a
 * trackpad — tap to click, long press for the secondary button, second finger to scroll — and
 * positions are sent to the host as a fraction of the view's size, so the host resolution
 * doesn't have to match the device's.
 *
 * <p>Two timers shape the feel of a tap. The touch-down deadzone timer delays the button press
 * slightly so that a tap which arrives and leaves within that window can be replayed at its
 * original position, which is what makes double-tapping reliable on a screen where the finger
 * never lands twice in exactly the same spot. The long press timer upgrades a held touch to the
 * secondary button.
 *
 * @see TouchContext for the call contract and coordinate/timebase conventions
 */
public class AbsoluteTouchContext implements TouchContext {
    private int lastTouchDownX = 0;
    private int lastTouchDownY = 0;
    private long lastTouchDownTime = 0;
    private int lastTouchUpX = 0;
    private int lastTouchUpY = 0;
    private long lastTouchUpTime = 0;
    private int lastTouchLocationX = 0;
    private int lastTouchLocationY = 0;
    private boolean cancelled;
    // A held touch that has been upgraded to the secondary button
    private boolean confirmedLongPress;
    // The primary button is down for this gesture
    private boolean confirmedTap;

    /** Upgrades a held touch from the primary to the secondary button. */
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            // This timer should have already expired, but cancel it just in case
            cancelTapDownTimer();

            // Switch from a left click to a right click after a long press
            confirmedLongPress = true;
            if (confirmedTap) {
                conn.sendMouseButtonUp(buttonPrimary);
            }
            conn.sendMouseButtonDown(buttonSecondary);
        }
    };

    /** Presses the primary button once the touch has outlived the double-tap deadzone window. */
    private final Runnable tapDownRunnable = new Runnable() {
        @Override
        public void run() {
            // Start our tap
            tapConfirmed();
        }
    };

    private final NvConnection conn;
    private final int actionIndex;
    private final View targetView;
    private final Handler handler;

    /** Releases the primary button for a tap too short to have released it inline. */
    private final Runnable leftButtonUpRunnable = new Runnable() {
        @Override
        public void run() {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    };

    // Gain applied to second-finger scroll deltas before they go to the host
    private static final int SCROLL_SPEED_FACTOR = 3;

    // How long a touch must be held, and how still it must stay, to become a long press
    private static final int LONG_PRESS_TIME_THRESHOLD = 650;
    private static final int LONG_PRESS_DISTANCE_THRESHOLD = 30;

    // A second touch inside this time and distance of the previous release is treated as the
    // second half of a double tap, and so is replayed at the first tap's position
    private static final int DOUBLE_TAP_TIME_THRESHOLD = 250;
    private static final int DOUBLE_TAP_DISTANCE_THRESHOLD = 60;

    // Movement is ignored for this long, and within this radius, after touch down. Fingers roll
    // slightly as they land, and without this the cursor would slide off the target mid-tap.
    private static final int TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100;
    private static final int TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20;

    // Button assignments, so left-handed users can swap primary and secondary
    private final byte buttonPrimary;
    private final byte buttonSecondary;

    /** Equivalent to {@link #AbsoluteTouchContext(NvConnection, int, View, boolean)} with no swap. */
    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view)
    {
        this(conn, actionIndex, view, false);
    }

    /**
     * @param view        view the touches are measured against; its size is sent to the host
     *                    alongside each position so the host can scale it to its own resolution
     * @param swapButtons exchange the primary and secondary buttons, so a tap right-clicks and
     *                    a long press left-clicks
     */
    public AbsoluteTouchContext(NvConnection conn, int actionIndex, View view, boolean swapButtons)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.targetView = view;
        this.handler = new Handler(Looper.getMainLooper());
        this.buttonPrimary = swapButtons ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT;
        this.buttonSecondary = swapButtons ? MouseButtonPacket.BUTTON_LEFT : MouseButtonPacket.BUTTON_RIGHT;
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger)
    {
        if (!isNewFinger) {
            // We don't handle finger transitions for absolute mode
            return true;
        }

        lastTouchLocationX = lastTouchDownX = eventX;
        lastTouchLocationY = lastTouchDownY = eventY;
        lastTouchDownTime = eventTime;
        cancelled = confirmedTap = confirmedLongPress = false;

        if (actionIndex == 0) {
            // Start the timers
            startTapDownTimer();
            startLongPressTimer();
        }

        return true;
    }

    /** @return true if the straight-line distance of the given delta exceeds {@code limit} pixels */
    private boolean distanceExceeds(int deltaX, int deltaY, double limit) {
        return Math.sqrt(Math.pow(deltaX, 2) + Math.pow(deltaY, 2)) > limit;
    }

    /** Sends an absolute cursor position, expressed relative to the target view's size. */
    private void updatePosition(int eventX, int eventY) {
        // We may get values slightly outside our view region on ACTION_HOVER_ENTER and ACTION_HOVER_EXIT.
        // Normalize these to the view size. We can't just drop them because we won't always get an event
        // right at the boundary of the view, so dropping them would result in our cursor never really
        // reaching the sides of the screen.
        eventX = Math.min(Math.max(eventX, 0), targetView.getWidth());
        eventY = Math.min(Math.max(eventY, 0), targetView.getHeight());

        conn.sendMousePosition((short)eventX, (short)eventY, (short)targetView.getWidth(), (short)targetView.getHeight());
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return;
        }

        if (actionIndex == 0) {
            // Cancel the timers
            cancelLongPressTimer();
            cancelTapDownTimer();

            // Raise the mouse buttons that we currently have down
            if (confirmedLongPress) {
                conn.sendMouseButtonUp(buttonSecondary);
            }
            else if (confirmedTap) {
                conn.sendMouseButtonUp(buttonPrimary);
            }
            else {
                // If we get here, this means that the tap completed within the touch down
                // deadzone time. We'll need to send the touch down and up events now at the
                // original touch down position.
                tapConfirmed();

                // Release the left mouse button in 100ms to allow for apps that use polling
                // to detect mouse button presses.
                handler.removeCallbacks(leftButtonUpRunnable);
                handler.postDelayed(leftButtonUpRunnable, 100);
            }
        }

        lastTouchLocationX = lastTouchUpX = eventX;
        lastTouchLocationY = lastTouchUpY = eventY;
        lastTouchUpTime = eventTime;
    }

    private void startLongPressTimer() {
        cancelLongPressTimer();
        handler.postDelayed(longPressRunnable, LONG_PRESS_TIME_THRESHOLD);
    }

    private void cancelLongPressTimer() {
        handler.removeCallbacks(longPressRunnable);
    }

    private void startTapDownTimer() {
        cancelTapDownTimer();
        handler.postDelayed(tapDownRunnable, TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD);
    }

    private void cancelTapDownTimer() {
        handler.removeCallbacks(tapDownRunnable);
    }

    /**
     * Presses the primary button for this gesture, repositioning the cursor first unless this
     * looks like the second half of a double tap. Idempotent, because it is reached both from
     * the deadzone timer and from the first move or release that outruns it.
     */
    private void tapConfirmed() {
        if (confirmedTap || confirmedLongPress) {
            return;
        }

        confirmedTap = true;
        cancelTapDownTimer();

        // Left button down at original position
        if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
                distanceExceeds(lastTouchDownX - lastTouchUpX, lastTouchDownY - lastTouchUpY, DOUBLE_TAP_DISTANCE_THRESHOLD)) {
            // Don't reposition for finger down events within the deadzone. This makes double-clicking easier.
            updatePosition(lastTouchDownX, lastTouchDownY);
        }
        conn.sendMouseButtonDown(buttonPrimary);
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        if (actionIndex == 0) {
            if (distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, LONG_PRESS_DISTANCE_THRESHOLD)) {
                // Moved too far since touch down. Cancel the long press timer.
                cancelLongPressTimer();
            }

            // Ignore motion within the deadzone period after touch down
            if (confirmedTap || distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD)) {
                tapConfirmed();
                updatePosition(eventX, eventY);
            }
        }
        else if (actionIndex == 1) {
            // The second finger scrolls vertically, using its movement since the last event
            conn.sendMouseHighResScroll((short)((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR));
        }

        lastTouchLocationX = eventX;
        lastTouchLocationY = eventY;

        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void cancelTouch() {
        cancelled = true;

        // Cancel the timers
        cancelLongPressTimer();
        cancelTapDownTimer();

        // Raise the mouse buttons
        if (confirmedLongPress) {
            conn.sendMouseButtonUp(buttonSecondary);
        }
        else if (confirmedTap) {
            conn.sendMouseButtonUp(buttonPrimary);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A second finger ends the primary gesture outright rather than modifying it: in this
     * mode the second finger is a scroll gesture, and leaving a click armed underneath it would
     * fire when the fingers lift.
     */
    @Override
    public void setPointerCount(int pointerCount) {
        if (actionIndex == 0 && pointerCount > 1) {
            cancelTouch();
        }
    }
}
