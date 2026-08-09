package com.limelight.binding.input.touch;

/**
 * Per-finger gesture state machine that turns local touch events into host mouse input.
 *
 * <p>One instance exists per pointer index for the lifetime of the stream, not per gesture:
 * {@code Game} keeps a fixed-size array (currently two, so only the first two fingers are
 * tracked) and routes each {@code MotionEvent} to the context whose {@link #getActionIndex()}
 * matches the event's pointer index. Instances therefore have to reset their own state when a
 * new gesture starts rather than relying on being reconstructed.
 *
 * <p>The implementation chosen decides what a touch <em>means</em>: {@link AbsoluteTouchContext}
 * maps the touch position straight to the host cursor, {@link RelativeTouchContext} and
 * {@link TrackpadContext} treat the screen as a trackpad and send deltas.
 *
 * <h2>Call contract</h2>
 * All methods are called from the UI thread, in this order for a typical gesture:
 * <ol>
 *   <li>{@link #setPointerCount(int)} on <em>every</em> context with the new total, then
 *       {@link #touchDownEvent} with {@code isNewFinger == true} on the context for the
 *       finger that went down.</li>
 *   <li>{@link #touchMoveEvent} on every context whose action index is below the current
 *       pointer count. Historical samples in the event are replayed first, so move events
 *       arrive more often than there are frames and must stay cheap.</li>
 *   <li>{@link #touchUpEvent} on the lifted finger's context, then
 *       {@link #setPointerCount(int)} on every context with the decremented total.</li>
 * </ol>
 *
 * <p>Two departures from that shape are worth knowing about:
 * <ul>
 *   <li><b>Finger promotion.</b> When the primary finger lifts while others remain down, the
 *       secondary finger takes its place: the <em>same</em> context (index 0) receives a
 *       second {@link #touchDownEvent} with {@code isNewFinger == false} at the new finger's
 *       position. Implementations that cannot meaningfully hand over mid-gesture ignore it
 *       (see {@link AbsoluteTouchContext#touchDownEvent}); implementations that can use it to
 *       decide what a release means (see {@link TrackpadContext#touchDownEvent}).</li>
 *   <li><b>Cancellation.</b> {@link #cancelTouch()} replaces the expected up event when the
 *       system takes the gesture away (palm rejection via {@code FLAG_CANCELED},
 *       {@code ACTION_CANCEL}) or when {@code Game} claims it for its own three-finger
 *       keyboard gesture. Any host mouse button still held down has to be released here,
 *       or the host is left with a stuck button.</li>
 * </ul>
 *
 * <p>Coordinates are pixels relative to the view the touch landed in, already offset to the
 * stream view's origin by the caller. Event times share {@code MotionEvent}'s
 * {@code SystemClock.uptimeMillis()} timebase and are the event's own timestamp, not the
 * time it was delivered — deriving velocity from wall-clock time instead would be wrong.
 */
public interface TouchContext {
    /**
     * @return the pointer index this context is responsible for: 0 for the first finger down,
     *         1 for the second. Used by the caller to route move events and by implementations
     *         to give the second finger a different role (right button, scrolling).
     */
    int getActionIndex();

    /**
     * Reports the number of fingers currently on screen, including fingers beyond the ones
     * with contexts. Called on every context whenever the count changes, before a down event
     * and after an up event, and with 0 when the gesture is cancelled.
     *
     * @param pointerCount total fingers down after the change
     */
    void setPointerCount(int pointerCount);

    /**
     * A finger went down on this context's pointer index.
     *
     * @param isNewFinger true for a genuine new touch, which should reset per-gesture state;
     *                    false when an existing finger has been promoted into this index
     *                    because the finger below it lifted, in which case the gesture is
     *                    continuing and its accumulated state should survive
     * @return true if the event was consumed
     */
    boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger);

    /**
     * The finger moved. Called for both historical and current samples, so this runs several
     * times per frame on high-report-rate panels.
     *
     * @return true if the event was consumed
     */
    boolean touchMoveEvent(int eventX, int eventY, long eventTime);

    /** The finger lifted normally. Deferred work (click release, momentum) may outlive this call. */
    void touchUpEvent(int eventX, int eventY, long eventTime);

    /**
     * The gesture was taken away rather than completed. Implementations must release any host
     * mouse button they are holding and abandon pending timers; no up event will follow.
     */
    void cancelTouch();

    /**
     * @return true if this gesture was cancelled. The caller checks this before promoting a
     *         finger, so a cancelled gesture is not resurrected by the handover path.
     */
    boolean isCancelled();
}
