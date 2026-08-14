package com.limelight.binding.input.driver;

import com.limelight.nvstream.jni.MoonBridge;

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

        // This is an Xbox pad reached over the wireless adapter, so it declares what every other
        // Xbox pad does. It extends AbstractController rather than AbstractXboxController - there is
        // no USB endpoint to claim here - which is how it ended up announcing nothing at all: the
        // host was told LI_CTYPE_UNKNOWN with no capabilities and no buttons, so it had no reason to
        // send rumble of either kind.
        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE |
                MoonBridge.LI_CCAP_TRIGGER_RUMBLE | MoonBridge.LI_CCAP_BATTERY_STATE;
        this.supportedButtonFlags = AbstractXboxController.XBOX_BUTTON_FLAGS;

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

    /**
     * Called from the native driver when the controller's battery state changes, with the raw GIP
     * values. The mapping lives here rather than in the driver because the constants it maps onto
     * are Moonlight's.
     *
     * <p>GIP's type field describes what kind of battery is fitted, not whether it is charging —
     * the protocol's status message carries no charge direction at all, so
     * {@code LI_BATTERY_STATE_CHARGING} is never reported. Type 0 means no battery: the pad is
     * running off the cable. xow's enum calls that value {@code BATT_TYPE_CHARGING}, which is a
     * misnomer; this follows xone's reading, which is the coherent one.
     *
     * @param type  GIP battery type, where 0 is "no battery fitted"
     * @param level GIP battery level, 0 to 3, meaningless when {@code type} is 0
     */
    public void updateBattery(byte type, byte level) {
        if (type == 0) {
            reportBattery(MoonBridge.LI_BATTERY_STATE_NOT_CHARGING,
                    MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN);
            return;
        }

        // GIP reports four buckets, not a percentage, so these are bucket midpoints rather than
        // measurements — the host's API has nowhere to express "one of four levels". Anything
        // finer would be invented precision the controller never reported.
        byte percentage;
        switch (level) {
            case 0:  percentage = 25; break;   // low
            case 1:  percentage = 50; break;   // normal
            case 2:  percentage = 75; break;   // high
            case 3:  percentage = 100; break;  // full
            default: percentage = MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN; break;
        }

        reportBattery(MoonBridge.LI_BATTERY_STATE_DISCHARGING, percentage);
    }

    native void registerNative(long handle);
    native void sendRumble(long handle, short lowFreqMotor, short highFreqMotor);
    native void sendrumbleTriggers(long handle, short leftTrigger, short rightTrigger);

}
