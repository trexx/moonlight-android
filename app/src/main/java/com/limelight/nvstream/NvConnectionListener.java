package com.limelight.nvstream;

/**
 * Callbacks from a {@link NvConnection} for the whole life of a stream.
 *
 * <p>Implemented by {@code Game}. Everything from connection progress to host-initiated events —
 * rumble, HDR mode changes, controller LED colours — arrives here.
 *
 * <p>Calls come from connection and native threads, never the UI thread, so implementations must
 * post before touching views.
 */
public interface NvConnectionListener {
    /** A connection stage has begun; the name is user-presentable. */
    void stageStarting(String stage);
    /** A connection stage finished successfully. */
    void stageComplete(String stage);
    // Returns true if the caller should retry the stage rather than give up. Only the
    // app launch stage honours this today; see NvConnection.start().
    boolean stageFailed(String stage, int portFlags, int errorCode);
    
    /** Every stage completed and the stream is live. */
    void connectionStarted();
    /** The stream ended. A zero error code means the host ended it deliberately. */
    void connectionTerminated(int errorCode);
    /** Connection quality changed, e.g. into or out of the poor-network state. */
    void connectionStatusUpdate(int connectionStatus);
    
    /** Shows a message the user must acknowledge. */
    void displayMessage(String message);
    /** Shows a message that disappears on its own. */
    void displayTransientMessage(String message);

    /** Host rumble request for one controller; amplitudes are unsigned 16-bit. */
    void rumble(short controllerNumber, short lowFreqMotor, short highFreqMotor);
    /** Host trigger rumble request, for controllers with trigger motors. */
    void rumbleTriggers(short controllerNumber, short leftTrigger, short rightTrigger);

    /** The host switched HDR on or off; the metadata is CTA-861.3 mastering data or null. */
    void setHdrMode(boolean enabled, byte[] hdrMetadata);

    /** The host wants motion reporting started or stopped; a rate of 0 means stop. */
    void setMotionEventState(short controllerNumber, byte motionType, short reportRateHz);

    /** The host set a controller's light bar colour. */
    void setControllerLED(short controllerNumber, byte r, byte g, byte b);
}
