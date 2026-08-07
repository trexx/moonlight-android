package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * Phone-style trackpad mouse mode: relative movement with pointer acceleration, two-finger
 * scrolling, tap-to-click, tap-and-drag and inertial flicking.
 *
 * <p>This is the touch mode that behaves like a laptop trackpad rather than like
 * {@link RelativeTouchContext}, whose gestures follow the older Moonlight conventions. The
 * differences that matter to a user are the cubic-root acceleration curve applied to every
 * move, momentum that continues after the finger lifts, and clicks derived from finger
 * <em>count</em> at release (one finger left, two right, three middle) instead of from which
 * pointer index the touch arrived on.
 *
 * <h2>Gesture state</h2>
 * A gesture is classified as it happens, and the flags below are sticky until the next
 * {@code isNewFinger} touch down resets them:
 * <ul>
 *   <li>{@code confirmedMove} — travelled far enough that this is no longer a candidate tap.</li>
 *   <li>{@code confirmedScroll} — a confirmed move by two fingers, which sends scroll wheel
 *       events instead of cursor movement.</li>
 *   <li>{@code confirmedDrag} — a button is being held down across the move, either from
 *       tap-then-drag or from flick momentum still carrying a held button.</li>
 *   <li>{@code isClickPending} / {@code isDblClickPending} — a tap has pressed a button that
 *       has not been released yet, so a second tap arriving inside the window becomes a
 *       double-click or a drag rather than two separate clicks.</li>
 * </ul>
 *
 * <h2>Deferred work</h2>
 * Momentum, click release and the post-scroll settling window all run as posted callbacks on
 * the main looper. They outlive the finger that started them, so they capture no event state
 * and re-check {@code isFlicking} before doing anything.
 *
 * @see TouchContext for the call contract and coordinate/timebase conventions
 */
public class TrackpadContext implements TouchContext {
    // Sub-pixel remainder carried between events. Deltas are accumulated as doubles and only
    // the whole-pixel part is sent, so slow movement isn't rounded away to nothing.
    private double pendingDeltaX = 0;
    private double pendingDeltaY = 0;

    // Position of the most recent move event, and of the touch down that began this gesture
    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;

    private boolean cancelled;

    // Gesture classification (see the class comment). All reset on a new finger down.
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedScroll;

    // Total path length in pixels, so a slow circling finger that never leaves the tap bounds
    // is still classified as a move
    private double distanceMoved;

    private int pointerCount;

    // Set when a middle click has already been emitted for this gesture, to stop the release
    // of the remaining fingers from also emitting a right click
    private boolean clickedMiddle = false;

    // Peak finger count of the gesture, which is what decides the button on release: fingers
    // rarely lift at the same instant, so the count at release time would under-report
    private int maxPointerCountInGesture;

    // A tap pressed a button that CLICK_RELEASE_DELAY has not yet released
    private boolean isClickPending;
    // Which button that was, so clickReleaseRunnable releases the one actually pressed
    private byte pendingClickButton;
    // A second tap arrived while a click was pending, so the release must complete a
    // double-click, or turn into a drag if the finger moves instead
    private boolean isDblClickPending;

    // Momentum is running: velocity is being decayed and applied without a finger on screen
    private boolean isFlicking;
    // Smoothed finger velocity in pixels/ms, post-acceleration and post-sensitivity
    private double velocityX = 0.0;
    private double velocityY = 0.0;
    private long lastMoveTime;

    // Set briefly after a two-finger scroll drops to one finger, to swallow the stray cursor
    // movement from the finger that hasn't lifted yet
    private boolean isScrollTransitioning = false;

    private final NvConnection conn;
    private final int actionIndex;
    private final Handler handler;

    // A touch that stays within this many pixels of its origin can still become a tap
    private static final int TAP_MOVEMENT_THRESHOLD = 30;
    // ...and only if it is released within this many milliseconds
    private static final int TAP_TIME_THRESHOLD = 230;
    // How long a tap's button press is held before being released, if no second tap arrives.
    // Also the window in which a second tap counts as a double-click, hence the shared value.
    private static final int CLICK_RELEASE_DELAY = TAP_TIME_THRESHOLD;
    // Scroll gain, per axis, applied to the high-resolution scroll deltas sent to the host
    private static final int SCROLL_SPEED_FACTOR_X = 2;
    private static final int SCROLL_SPEED_FACTOR_Y = 3;
    // Pixels of travel per event at which acceleration is neutral: below this the cube root
    // shrinks the delta for precision, above it magnifies for reach
    private static final double ACCELERATION_THRESHOLD = 8.0;
    // Velocity retained per momentum frame, i.e. ~7% shed every MOMENTUM_FRAME_INTERVAL_MS
    private static final double FLICK_FRICTION = 0.93;
    // Unit: pixels/ms.
    private static final double FLICK_THRESHOLD = 0.8;
    private static final int MOMENTUM_FRAME_INTERVAL_MS = 10;
    // Velocity is faded out over this long a pause before release, so resting the finger
    // before lifting it cancels the flick instead of launching a stale one
    private static final int FLICK_VELOCITY_DECAY_TIMEOUT_MS = 50;
    // How long cursor movement stays blocked after a two-finger scroll ends
    private static final int SCROLL_TRANSITION_TIMEOUT_MS = 200;

    public TrackpadContext(NvConnection conn, int actionIndex) {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Releases a tap's button once the double-click window has passed without a second tap.
     *
     * <p>A field rather than a lambda captured at the call site so that the paths which have to
     * take the button over — the drag and double-click branches of {@link #touchUpEvent} — can
     * cancel this one callback by reference instead of clearing everything posted to the handler.
     */
    private final Runnable clickReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (isClickPending) {
                conn.sendMouseButtonUp(pendingClickButton);
                isClickPending = false;
            }
            isDblClickPending = false;
        }
    };

    /** Ends the post-scroll settling window; see {@link #setPointerCount(int)}. */
    private final Runnable scrollTransitionRunnable = new Runnable() {
        @Override
        public void run() {
            isScrollTransitioning = false;
        }
    };

    /**
     * Cursor momentum. Re-posts itself every {@link #MOMENTUM_FRAME_INTERVAL_MS} until friction
     * brings the velocity below half a pixel per frame, sending the decaying velocity as mouse
     * movement. If the flick began during a drag, the held button is released only once the
     * momentum has run out, so the drag lands where the cursor stops rather than where the
     * finger lifted.
     */
    private final Runnable momentumRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFlicking) {
                return;
            }

            pendingDeltaX += velocityX * MOMENTUM_FRAME_INTERVAL_MS;
            pendingDeltaY += velocityY * MOMENTUM_FRAME_INTERVAL_MS;

            short intDeltaX = (short) pendingDeltaX;
            short intDeltaY = (short) pendingDeltaY;

            if (intDeltaX != 0 || intDeltaY != 0) {
                conn.sendMouseMove(intDeltaX, intDeltaY);
                pendingDeltaX -= intDeltaX;
                pendingDeltaY -= intDeltaY;
            }

            velocityX *= FLICK_FRICTION;
            velocityY *= FLICK_FRICTION;

            if (Math.sqrt(velocityX * velocityX + velocityY * velocityY) * MOMENTUM_FRAME_INTERVAL_MS < 0.5) {
                isFlicking = false;
                if (confirmedDrag) {
                    conn.sendMouseButtonUp(getMouseButtonIndex());
                    confirmedDrag = false;
                }
            }

            if (isFlicking) {
                handler.postDelayed(this, MOMENTUM_FRAME_INTERVAL_MS);
            }
        }
    };

    /**
     * Scroll momentum, the two-finger counterpart of {@link #momentumRunnable}. The dominant
     * axis always scrolls; the other one only joins in when the gesture was close to diagonal
     * (within 5%), which keeps a nearly-vertical flick from drifting sideways. Horizontal
     * deltas are negated because a finger moving right scrolls the content left.
     */
    private final Runnable scrollMomentumRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFlicking) {
                return;
            }

            double frameVelocityX = velocityX * MOMENTUM_FRAME_INTERVAL_MS;
            double frameVelocityY = velocityY * MOMENTUM_FRAME_INTERVAL_MS;

            if (Math.abs(frameVelocityX) > Math.abs(frameVelocityY)) {
                conn.sendMouseHighResHScroll((short)(-frameVelocityX * SCROLL_SPEED_FACTOR_X));
                if (Math.abs(frameVelocityY) * 1.05 > Math.abs(frameVelocityX)) {
                    conn.sendMouseHighResScroll((short)(frameVelocityY * SCROLL_SPEED_FACTOR_Y));
                }
            } else {
                conn.sendMouseHighResScroll((short)(frameVelocityY * SCROLL_SPEED_FACTOR_Y));
                if (Math.abs(frameVelocityX) * 1.05 >= Math.abs(frameVelocityY)) {
                    conn.sendMouseHighResHScroll((short)(-frameVelocityX * SCROLL_SPEED_FACTOR_X));
                }
            }

            velocityX *= FLICK_FRICTION;
            velocityY *= FLICK_FRICTION;

            if (Math.sqrt(velocityX * velocityX + velocityY * velocityY) * MOMENTUM_FRAME_INTERVAL_MS < 0.5) {
                isFlicking = false;
            }

            if (isFlicking) {
                handler.postDelayed(this, MOMENTUM_FRAME_INTERVAL_MS);
            }
        }
    };

    /** {@inheritDoc} */
    @Override
    public int getActionIndex() {
        return actionIndex;
    }

    /**
     * Stops both momentum runnables. Targeted rather than clearing the whole handler queue, so
     * that an unrelated pending callback — the post-scroll settling window in particular — is
     * left to run out on its own.
     */
    private void cancelMomentum() {
        handler.removeCallbacks(momentumRunnable);
        handler.removeCallbacks(scrollMomentumRunnable);
    }

    /** @return true if the touch is still close enough to its origin to become a tap */
    private boolean isWithinTapBounds(int touchX, int touchY) {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    /**
     * @return true if the gesture ending at {@code eventTime} should produce a click. Only the
     *         last finger of a multi-finger tap qualifies (hence the action index check),
     *         otherwise a two-finger tap would emit a click for each finger.
     */
    private boolean isTap(long eventTime) {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false;
        }

        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    /**
     * Maps the current finger count onto a mouse button: one finger left, two right, three
     * middle.
     *
     * <p>Whoever emits a middle click is responsible for setting {@link #clickedMiddle}, which
     * suppresses the right click that would otherwise follow as the remaining fingers lift.
     */
    private byte getMouseButtonIndex() {
        if (pointerCount == 2) {
            return MouseButtonPacket.BUTTON_RIGHT;
        } else if (pointerCount == 3) {
            return MouseButtonPacket.BUTTON_MIDDLE;
        } else {
            return MouseButtonPacket.BUTTON_LEFT;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Touching the screen during a flick stops it, the way it does on a real trackpad.
     *
     * <p>The {@code isNewFinger == false} path is the finger-promotion case from
     * {@link TouchContext}, which here means a finger was <em>released</em> while others stayed
     * down. That is how multi-finger taps are detected: releasing from three fingers emits the
     * middle click and releasing from two emits the right click immediately, rather than
     * waiting for every finger to leave the screen.
     */
    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger) {
        if (isFlicking) {
            isFlicking = false;
            cancelMomentum();
        }

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

        pendingDeltaX = pendingDeltaY = 0;

        if (isNewFinger) {
            // A completely new gesture has started.
            // Cancel any pending scroll->move transition.
            clickedMiddle = false;
            handler.removeCallbacks(scrollTransitionRunnable);
            isScrollTransitioning = false;
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedMove = confirmedScroll = false;
            distanceMoved = 0;
            velocityX = 0;
            velocityY = 0;
            lastMoveTime = eventTime;
            // A finger landing while the previous tap's button is still held turns that click
            // into either a double-click (if this finger is also tapped) or a drag (if it
            // moves), which is why the button is left down here
            if (isClickPending) {
                isClickPending = false;
                isDblClickPending = true;
                confirmedDrag = true;
            }
        } else {
            // Three fingers down to two: the gesture was a middle click
            if (pointerCount == 2 && !confirmedMove) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE);
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                isClickPending = false;
                isDblClickPending = false;
                confirmedDrag = false;
                clickedMiddle = true;
            // Second finger released, should trigger right click immediately
            // (suppressed if this gesture already emitted a middle click on the way down)
            } else if (pointerCount == 1 && !confirmedMove && !clickedMiddle) {
                conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                isClickPending = false;
                isDblClickPending = false;
                confirmedDrag = false;
            }
        }

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Decides what the completed gesture meant, in priority order: finish a pending
     * double-click, end a drag (possibly by handing it to momentum), emit a click for a tap,
     * or start a flick for a move or scroll that was still travelling.
     */
    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime) {
        if (cancelled) {
            return;
        }

        // Decay velocity based on time since last move event to avoid
        // flicks when the user pauses before lifting their finger.
        long timeSinceLastMove = eventTime - lastMoveTime;
        if (timeSinceLastMove > 0) {
            double decay = Math.max(0.0, 1.0 - (double)timeSinceLastMove / FLICK_VELOCITY_DECAY_TIMEOUT_MS);
            velocityX *= decay;
            velocityY *= decay;
        }

        byte buttonIndex = getMouseButtonIndex();

        // Record a middle click so the right click that would otherwise be emitted as the
        // remaining fingers lift is suppressed
        if (buttonIndex == MouseButtonPacket.BUTTON_MIDDLE) {
            clickedMiddle = true;
        }

        if (isDblClickPending) {
            // The first click's button is still held, so releasing it and issuing a full
            // press/release pair gives the host the second click of a double-click
            handler.removeCallbacks(clickReleaseRunnable);
            conn.sendMouseButtonUp(buttonIndex);
            conn.sendMouseButtonDown(buttonIndex);
            conn.sendMouseButtonUp(buttonIndex);
            isClickPending = false;
            confirmedDrag = false;
        }
        else if (confirmedDrag) {
            handler.removeCallbacks(clickReleaseRunnable);

            // A drag released at speed keeps the button down and hands over to momentum,
            // which releases it when the cursor comes to rest
            double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
            if (speed > FLICK_THRESHOLD) {
                isFlicking = true;
                handler.post(momentumRunnable);
            } else {
                conn.sendMouseButtonUp(buttonIndex);
                confirmedDrag = false;
            }
        }
        else if (isTap(eventTime)) {
            // The button goes down now but is released on a timer, leaving a window in which
            // a second tap can upgrade this into a double-click or a drag
            conn.sendMouseButtonDown(buttonIndex);
            isClickPending = true;
            pendingClickButton = buttonIndex;

            handler.removeCallbacks(clickReleaseRunnable);
            handler.postDelayed(clickReleaseRunnable, CLICK_RELEASE_DELAY);
        }
        else if (confirmedMove) {
            // This was a move/scroll that wasn't a drag or tap. Let's see if we should flick.
            double speed = Math.sqrt(velocityX * velocityX + velocityY * velocityY);
            if (speed > FLICK_THRESHOLD) {
                isFlicking = true;
                if (confirmedScroll) {
                    handler.post(scrollMomentumRunnable);
                } else {
                    // A 1-finger move can flick. A >1 finger move that wasn't a scroll shouldn't cause a mouse move flick.
                    if (maxPointerCountInGesture == 1) {
                        handler.post(momentumRunnable);
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies pointer acceleration, then routes the result to cursor movement or to scroll
     * events depending on how many fingers are down and how the gesture was classified.
     */
    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime) {
        if (cancelled) {
            return true;
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            long deltaTime = eventTime - lastMoveTime;

            checkForConfirmedMove(eventX, eventY);

            // Movement during the double-click window means the user is dragging, not
            // double-clicking, so keep the button held and let the move carry it
            if (isDblClickPending) {
                isDblClickPending = false;
                confirmedDrag = true;
            }

            int rawDeltaX = eventX - lastTouchX;
            int rawDeltaY = eventY - lastTouchY;
            int absDeltaX = Math.abs(rawDeltaX);
            int absDeltaY = Math.abs(rawDeltaY);

            // Pointer acceleration. The cube root of the normalised travel gives a gentle
            // curve: below ACCELERATION_THRESHOLD pixels per event the multiplier is under 1
            // for fine positioning, above it the multiplier grows so the cursor can cross the
            // host screen without repeated swipes.
            double magnitude = Math.sqrt(rawDeltaX * rawDeltaX + rawDeltaY * rawDeltaY);
            double precisionMultiplier = Math.cbrt(magnitude / ACCELERATION_THRESHOLD);

            float deltaX = (float)(rawDeltaX * precisionMultiplier);
            float deltaY = (float)(rawDeltaY * precisionMultiplier);

            // Update velocity for flicking. Measured after acceleration, so FLICK_THRESHOLD is
            // in the same units as the deltas actually sent to the host.
            if (deltaTime > 0 && (confirmedMove || confirmedDrag)) {
                double currentVelocityX = deltaX / deltaTime;
                double currentVelocityY = deltaY / deltaTime;

                if (velocityX == 0 && velocityY == 0) {
                    // First measurement
                    velocityX = currentVelocityX;
                    velocityY = currentVelocityY;
                } else {
                    // Simple EMA for smoothing
                    velocityX = velocityX * 0.8 + currentVelocityX * 0.2;
                    velocityY = velocityY * 0.8 + currentVelocityY * 0.2;
                }
            }

            lastMoveTime = eventTime;

            pendingDeltaX += deltaX;
            pendingDeltaY += deltaY;

            lastTouchX = eventX;
            lastTouchY = eventY;

            short sendDeltaX = (short)pendingDeltaX;
            short sendDeltaY = (short)pendingDeltaY;

            if (pointerCount == 1) {
                // Don't move the mouse immediately after a scroll
                if (!isScrollTransitioning) {
                    if (sendDeltaX != 0 || sendDeltaY != 0) {
                        conn.sendMouseMove(sendDeltaX, sendDeltaY);
                    }
                }
            } else {
                // With more than one finger down, only the second context drives the gesture:
                // the primary context's events would otherwise double up the scroll deltas
                if (actionIndex == 1) {
                    if (confirmedDrag) {
                        if (sendDeltaX != 0 || sendDeltaY != 0) {
                            conn.sendMouseMove(sendDeltaX, sendDeltaY);
                        }
                    } else if (pointerCount == 2) {
                        checkForConfirmedScroll();
                        if (confirmedScroll) {
                            // Dominant axis scrolls; the other only joins in on a near-diagonal
                            // gesture, matching scrollMomentumRunnable
                            if (absDeltaX > absDeltaY) {
                                conn.sendMouseHighResHScroll((short)(-sendDeltaX * SCROLL_SPEED_FACTOR_X));
                                if (absDeltaY * 1.05 > absDeltaX) {
                                    conn.sendMouseHighResScroll((short)(sendDeltaY * SCROLL_SPEED_FACTOR_Y));
                                }
                            } else {
                                conn.sendMouseHighResScroll((short)(sendDeltaY * SCROLL_SPEED_FACTOR_Y));
                                if (absDeltaX * 1.05 >= absDeltaY) {
                                    conn.sendMouseHighResHScroll((short)(-sendDeltaX * SCROLL_SPEED_FACTOR_X));
                                }
                            }
                        }
                    }
                }
            }

            // Keep the fractional remainder so sub-pixel movement accumulates instead of
            // being discarded. Subtracted unconditionally: the deltas count as consumed even
            // on the paths above that chose not to send them.
            pendingDeltaX -= sendDeltaX;
            pendingDeltaY -= sendDeltaY;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stops momentum as well as the gesture, since a cancelled gesture that kept flicking
     * would move the host cursor after the user's input was rejected.
     */
    @Override
    public void cancelTouch() {
        cancelled = true;

        if (isFlicking) {
            isFlicking = false;
            cancelMomentum();
        }

        if (confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
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
     * <p>Also handles the transitions between finger counts: opening and closing the
     * post-scroll settling window, and releasing a held button when the user lifts a finger
     * mid-drag so the host isn't left with a stuck button.
     */
    @Override
    public void setPointerCount(int pointerCount) {
        if (this.pointerCount == 2 && pointerCount == 1) {
            // We just finished a 2-finger scroll.
            // Block mouse movement for a short period to avoid stray movement
            // from the remaining finger before it's lifted.
            isScrollTransitioning = true;
            handler.postDelayed(scrollTransitionRunnable, SCROLL_TRANSITION_TIMEOUT_MS);
        } else if (this.pointerCount == 1 && pointerCount == 2) {
            // User put a second finger down. If we were in a scroll transition, cancel it.
            handler.removeCallbacks(scrollTransitionRunnable);
            isScrollTransitioning = false;
        }

        // Fingers coming off mid-drag ends the drag, unless momentum has taken it over — in
        // that case momentumRunnable owns the button and releases it when it stops
        if (pointerCount < this.pointerCount && confirmedDrag && !isFlicking) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
            confirmedDrag = false;
            confirmedMove = false;
            confirmedScroll = false;
            isClickPending = false;
            isDblClickPending = false;
        }

        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }

    /**
     * Promotes the gesture to a move once it can no longer be a tap, either by straying
     * outside the tap bounds or by accumulating enough total travel while staying inside them.
     */
    private void checkForConfirmedMove(int eventX, int eventY) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag) {
            return;
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true;
            return;
        }

        // Check if we've exceeded the maximum distance moved
        distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        if (distanceMoved >= TAP_MOVEMENT_THRESHOLD) {
            confirmedMove = true;
        }
    }

    /**
     * Enters or leaves scroll mode. Recomputed rather than latched, so lifting a finger drops
     * the gesture straight back out of scrolling.
     */
    private void checkForConfirmedScroll() {
        confirmedScroll = (actionIndex == 1 && pointerCount == 2 && confirmedMove);
    }
}
