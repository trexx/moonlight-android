package com.limelight.binding.input.driver;

/**
 * Sink for events from the USB controllers this app drives itself.
 *
 * <p>Implemented twice: by {@link UsbDriverService}, which forwards to whichever client is bound,
 * and by that client (normally {@code Game}) to feed {@code ControllerHandler}.
 *
 * <p>All callbacks arrive on the controller's own reader thread, not the UI thread.
 */
public interface UsbDriverListener {
    /**
     * Reports the complete current state of a controller. Sent on every input report, so
     * implementations should expect these at the controller's full polling rate.
     *
     * @param controllerId the reporting controller's {@link AbstractController#getControllerId()}
     * @param buttonFlags  bitfield of {@code ControllerPacket.*_FLAG} values
     * @param leftTrigger  0.0 to 1.0
     * @param rightTrigger 0.0 to 1.0
     */
    void reportControllerState(int controllerId, int buttonFlags,
                               float leftStickX, float leftStickY,
                               float rightStickX, float rightStickY,
                               float leftTrigger, float rightTrigger);

    /**
     * Reports one motion sensor sample.
     *
     * @param motionType {@code MoonBridge.LI_MOTION_TYPE_GYRO} in deg/s, or
     *                   {@code LI_MOTION_TYPE_ACCEL} in m/s^2 including gravity
     */
    void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ);

    /**
     * Reports a change in the controller's battery. Sent only when the level actually moves, not
     * on a timer, so implementations should not expect a steady cadence.
     *
     * @param batteryState      one of {@code MoonBridge.LI_BATTERY_STATE_*}
     * @param batteryPercentage 0 to 100, or {@code MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN}
     */
    void reportControllerBattery(int controllerId, byte batteryState, byte batteryPercentage);

    /** The controller is gone and its host slot should be released. */
    void deviceRemoved(AbstractController controller);

    /** The controller is initialised and about to start reporting input. */
    void deviceAdded(AbstractController controller);
}
