package com.limelight.binding.input.driver;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * Base class for controllers this app drives over USB itself, bypassing Android's input stack.
 *
 * <p>Taking a device over is only worth it when the kernel driver is absent or worse than what
 * we can do directly — no rumble, no motion sensors, wrong button mapping. {@link UsbDriverService}
 * owns that decision and the device lifecycle; subclasses only implement the wire protocol.
 *
 * <p>The pattern is: parse a report into the protected state fields below, then call
 * {@link #reportInput()} (and {@link #reportMotion()} for sensor-capable pads) to push the whole
 * state onward. State is cumulative between reports, so a subclass only has to write the fields
 * its report actually carries.
 *
 * <p>Subclasses run their own reader thread, so the reporting methods are called off the UI
 * thread and the state fields are written from that thread.
 */
public abstract class AbstractController {

    // Identity assigned by UsbDriverService, unrelated to Android's InputDevice IDs
    private final int deviceId;
    private final int vendorId;
    private final int productId;

    private UsbDriverListener listener;

    // Current controller state, written by the subclass's parser and read by reportInput().
    // Buttons are a bitfield of ControllerPacket.*_FLAG values.
    protected int buttonFlags, supportedButtonFlags;
    // Triggers are 0.0 to 1.0; sticks are -1.0 to 1.0 with Y positive upwards
    protected float leftTrigger, rightTrigger;
    protected float rightStickX, rightStickY;
    protected float leftStickX, leftStickY;
    // Motion state in the protocol's units: deg/s and m/s^2. See reportMotion().
    protected float gyroX, gyroY, gyroZ;
    protected float accelX, accelY, accelZ;
    // MoonBridge.LI_CCAP_* bitfield advertised to the host, set by the subclass's constructor
    protected short capabilities;
    // MoonBridge.LI_CTYPE_*, which tells the host which button glyphs to display
    protected byte type;

    /** @return this driver's own controller ID, as assigned by {@link UsbDriverService} */
    public int getControllerId() {
        return deviceId;
    }

    /** @return the USB vendor ID of the physical device */
    public int getVendorId() {
        return vendorId;
    }

    /** @return the USB product ID of the physical device */
    public int getProductId() {
        return productId;
    }

    /** @return bitfield of the buttons this controller physically has */
    public int getSupportedButtonFlags() {
        return supportedButtonFlags;
    }

    /** @return {@code MoonBridge.LI_CCAP_*} bitfield of rumble and motion capabilities */
    public short getCapabilities() {
        return capabilities;
    }

    /** @return {@code MoonBridge.LI_CTYPE_*} identifying the controller family to the host */
    public byte getType() {
        return type;
    }

    /**
     * Sets or clears a button in {@link #buttonFlags}.
     *
     * @param data the raw masked report bits; any non-zero value counts as pressed, so callers
     *             can pass the result of masking a report byte without normalising it first
     */
    protected void setButtonFlag(int buttonFlag, int data) {
        if (data != 0) {
            buttonFlags |= buttonFlag;
        }
        else {
            buttonFlags &= ~buttonFlag;
        }
    }

    /** Pushes the current button, stick and trigger state to the listener. */
    protected void reportInput() {
        listener.reportControllerState(deviceId, buttonFlags, leftStickX, leftStickY,
                rightStickX, rightStickY, leftTrigger, rightTrigger);
    }

    /**
     * Reports the current motion sensor state. Subclasses that set LI_CCAP_GYRO or
     * LI_CCAP_ACCEL are responsible for populating the fields in the protocol's units:
     * deg/s for gyro, and m/s^2 (inclusive of gravity) for accel.
     */
    protected void reportMotion() {
        listener.reportControllerMotion(deviceId, MoonBridge.LI_MOTION_TYPE_GYRO, gyroX, gyroY, gyroZ);
        listener.reportControllerMotion(deviceId, MoonBridge.LI_MOTION_TYPE_ACCEL, accelX, accelY, accelZ);
    }

    /**
     * Reports battery state. Only for subclasses that set {@code LI_CCAP_BATTERY_STATE}; the host
     * displays whatever arrives, so a subclass that cannot determine a real percentage should send
     * {@link MoonBridge#LI_BATTERY_PERCENTAGE_UNKNOWN} rather than a plausible-looking guess.
     */
    protected void reportBattery(byte batteryState, byte batteryPercentage) {
        listener.reportControllerBattery(deviceId, batteryState, batteryPercentage);
    }

    /**
     * Claims the USB interfaces and starts the reader thread.
     *
     * @return true if the device was claimed. On false the caller closes the connection, and
     *         {@link #stop()} will not be called.
     */
    public abstract boolean start();

    /**
     * Stops the reader thread, releases the claimed interfaces and closes the connection, so
     * that Android's own driver can take the device back. Must tolerate being called twice and
     * from either the reader thread or the service.
     */
    public abstract void stop();

    /**
     * @param deviceId identity assigned by {@link UsbDriverService}
     * @param listener sink for input, motion and device lifecycle events
     */
    public AbstractController(int deviceId, UsbDriverListener listener, int vendorId, int productId) {
        this.deviceId = deviceId;
        this.listener = listener;
        this.vendorId = vendorId;
        this.productId = productId;
    }

    /**
     * Sets the rumble motors. Called from the host's rumble callback, not the reader thread.
     *
     * @param lowFreqMotor  heavy motor intensity, 0 to 65535 as an unsigned short
     * @param highFreqMotor light motor intensity, 0 to 65535 as an unsigned short
     */
    public abstract void rumble(short lowFreqMotor, short highFreqMotor);

    /**
     * Sets the trigger rumble motors, on controllers that have them. Implementations without
     * them ignore this rather than approximating it with the main motors.
     */
    public abstract void rumbleTriggers(short leftTrigger, short rightTrigger);

    /** Announces that this controller is gone, so the host drops its slot. */
    protected void notifyDeviceRemoved() {
        listener.deviceRemoved(this);
    }

    /**
     * Announces that this controller is ready for input. Called once the device is fully
     * initialised rather than when it is claimed, so the host doesn't see a controller that
     * cannot yet report anything.
     */
    protected void notifyDeviceAdded() {
        listener.deviceAdded(this);
    }
}
