package com.limelight.binding.input.driver;

/**
 * One controller paired to an {@link XboxWirelessDongle}.
 *
 * <p>Inverted from every other {@link AbstractController}: there is no reader thread and no USB
 * traffic here, because the native {@code xow-driver} owns the wireless link and pushes state in
 * through {@link #updateInput}. This class only translates that into the protocol's units and
 * routes rumble back down.
 */
public class XboxWirelessController extends AbstractController{
    static {
        System.loadLibrary("xow-driver");
    }

    // Native controller instance owned by the driver, valid until the driver removes it
    private final long handle;

    /** @param handle native controller instance supplied by the dongle's driver */
    public XboxWirelessController(int deviceId, UsbDriverListener listener, int vendorId, int productId, long handle) {
        super(deviceId, listener, vendorId, productId);
        this.handle = handle;
        registerNative(this.handle);
    }

    /** {@inheritDoc} Nothing to claim: the dongle's driver already owns the link. */
    @Override
    public boolean start() {
        // do nothing since mt driver will handle it.
        return true;
    }

    /** {@inheritDoc} Teardown belongs to the dongle, which removes this controller instead. */
    @Override
    public void stop() {
        // do nothing since mt driver will handle it.
    }

    /** {@inheritDoc} Routed through the native driver rather than over USB directly. */
    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        sendRumble(handle, lowFreqMotor, highFreqMotor);
    }

    /** {@inheritDoc} Routed through the native driver. */
    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        sendrumbleTriggers(handle, leftTrigger, rightTrigger);
    }

    /**
     * Called from the native driver with a complete controller state, which is normalised into
     * the protocol's ranges and reported onward.
     *
     * @param buttons      bitfield already in {@code ControllerPacket.*_FLAG} form
     * @param triggerLeft  10-bit trigger value
     * @param triggerRight 10-bit trigger value
     */
    public void updateInput(int buttons,short triggerLeft, short triggerRight,
                            short stickLeftX, short stickLeftY,
                            short stickRightX, short stickRightY) {
        buttonFlags = buttons;
        leftTrigger = triggerLeft / 1023.0f;
        rightTrigger = triggerRight / 1023.0f;
        leftStickX = stickLeftX / 32767.0f;
        leftStickY = stickLeftY / -32767.0f;
        rightStickX = stickRightX / 32767.0f;
        rightStickY = stickRightY / -32767.0f;

        reportInput();
    }

    native void registerNative(long handle);
    native void sendRumble(long handle, short lowFreqMotor, short highFreqMotor);
    native void sendrumbleTriggers(long handle, short leftTrigger, short rightTrigger);

}
