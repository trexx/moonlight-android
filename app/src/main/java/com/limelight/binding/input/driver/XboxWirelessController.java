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
     * <p>The status byte packs four fields (MS-GIPUSB Table 30). Type says what kind of battery is
     * fitted, <em>not</em> whether it is charging — that is a separate field, which xow's struct
     * discards into {@code connectionInfo} and which the driver now decodes. Type 0 means no
     * battery at all: the pad is running off the cable. xow's enum calls that value
     * {@code BATT_TYPE_CHARGING}, which is a misnomer the spec settles.
     *
     * @param type   GIP battery type: 0 absent or bus powered, 1 standard, 2 rechargeable
     * @param level  GIP battery level, 0 to 3, meaningless when {@code type} is 0
     * @param charge GIP charge state: 0 not charging, 1 charging, 2 charge error
     */
    public void updateBattery(byte type, byte level, byte charge) {
        // No battery fitted: the pad is running off the cable, and its level means nothing
        if (type == 0) {
            reportBattery(MoonBridge.LI_BATTERY_STATE_NOT_CHARGING,
                    MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN);
            return;
        }

        byte state;
        switch (charge) {
            case 1:  state = MoonBridge.LI_BATTERY_STATE_CHARGING; break;
            // Connected to power but not taking it, which is what NOT_CHARGING describes
            case 2:  state = MoonBridge.LI_BATTERY_STATE_NOT_CHARGING; break;
            default: state = MoonBridge.LI_BATTERY_STATE_DISCHARGING; break;
        }

        // The percentages are the spec's own, not invented midpoints: MS-GIPUSB Table 30 defines
        // level 01 as "approximately 25% charge remaining", 10 as halfway through a 50% depletion
        // estimate, and 11 as close to full. Level 00 is "less than 2 hours of charge remaining"
        // with no percentage attached, so it takes a low value that reads as a warning.
        byte percentage;
        switch (level) {
            case 0:  percentage = 10; break;   // critically low
            case 1:  percentage = 25; break;   // low
            case 2:  percentage = 50; break;   // medium
            case 3:  percentage = 100; break;  // full
            default: percentage = MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN; break;
        }

        reportBattery(state, percentage);
    }

    /**
     * @return true if this pad told us, in its metadata, that it can render 48 kHz stereo. A pad
     *         that did not has no audio endpoint reachable this way, and enabling would move the
     *         stream's audio off the TV and into silence.
     */
    public boolean hasAudioSupport() {
        return hasAudioSupportNative(handle);
    }

    /**
     * Starts or stops rendering stream audio to this pad's headphone jack.
     *
     * @return true if the pad accepted the change
     */
    public boolean setAudioEnabled(boolean enable) {
        return setAudioEnabledNative(handle, enable);
    }

    /**
     * Queues interleaved 16-bit stereo PCM for this pad.
     *
     * <p>Called from Moonlight's audio decode thread. The native side copies into a bounded ring
     * and returns, so this does not block on the wireless link.
     *
     * @param audioData interleaved samples; the caller reuses the buffer, so it is not retained
     * @param count     number of samples to take from the front of {@code audioData}
     */
    public void queueAudio(short[] audioData, int count) {
        queueAudioNative(handle, audioData, count);
    }

    native void registerNative(long handle);
    native boolean setAudioEnabledNative(long handle, boolean enable);
    native boolean hasAudioSupportNative(long handle);
    native void queueAudioNative(long handle, short[] samples, int count);
    native void sendRumble(long handle, short lowFreqMotor, short highFreqMotor);
    native void sendrumbleTriggers(long handle, short leftTrigger, short rightTrigger);

}
