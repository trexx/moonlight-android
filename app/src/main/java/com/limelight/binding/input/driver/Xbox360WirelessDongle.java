package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.view.InputDevice;

import com.limelight.LimeLog;


/**
 * Xbox 360 wireless receiver handler that sets player LEDs and then deliberately gets out of the
 * way.
 *
 * <p>It is an {@link AbstractController} only to fit the claiming machinery in
 * {@link UsbDriverService}; it never reports input. The kernel's xpad driver handles these
 * controllers perfectly well, but on kernels built without {@code CONFIG_JOYSTICK_XPAD_LEDS} it
 * never assigns a player number, leaving the pad's ring flashing forever. So this claims each
 * interface just long enough to send the LED command, then releases it and returns false from
 * {@link #start()} so xpad takes the device straight back.
 *
 * <p>The player number is inferred from Android's own controller numbering, which is only an
 * approximation — see {@link #start()}.
 */
public class Xbox360WirelessDongle extends AbstractController {
    private UsbDevice device;
    private UsbDeviceConnection connection;

    // Interface descriptor values that identify the wireless receiver's controller interfaces
    private static final int XB360W_IFACE_SUBCLASS = 93;
    private static final int XB360W_IFACE_PROTOCOL = 129; // Wireless only

    private static final int[] SUPPORTED_VENDORS = {
            0x045e, // Microsoft
    };

    /** @return true if this is a Microsoft Xbox 360 wireless receiver */
    public static boolean canClaimDevice(UsbDevice device) {
        for (int supportedVid : SUPPORTED_VENDORS) {
            if (device.getVendorId() == supportedVid &&
                    device.getInterfaceCount() >= 1 &&
                    device.getInterface(0).getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).getInterfaceSubclass() == XB360W_IFACE_SUBCLASS &&
                    device.getInterface(0).getInterfaceProtocol() == XB360W_IFACE_PROTOCOL) {
                return true;
            }
        }

        return false;
    }

    /** {@inheritDoc} */
    public Xbox360WirelessDongle(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
    }

    /**
     * Lights the quadrant for {@code controllerIndex} on one wireless slot.
     *
     * @param controllerIndex zero-based player number; wraps at 4, which is all the ring has
     */
    private void sendLedCommandToEndpoint(UsbEndpoint endpoint, int controllerIndex) {
        byte[] commandBuffer = {
                0x00,
                0x00,
                0x08,
                (byte) (0x40 + (2 + (controllerIndex % 4))),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00};

        int res = connection.bulkTransfer(endpoint, commandBuffer, commandBuffer.length, 3000);
        if (res != commandBuffer.length) {
            LimeLog.warning("LED set transfer failed: "+res);
        }
    }

    /**
     * Sends the LED command on one interface, borrowing it from xpad for the duration. Failures
     * are logged and skipped: a slot whose LED can't be set is no worse off than before.
     */
    private void sendLedCommandToInterface(UsbInterface iface, int controllerIndex) {
        // Claim this interface to kick xpad off it (temporarily)
        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("Failed to claim interface: "+iface.getId());
            return;
        }

        // Find the out endpoint for this interface
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_OUT) {
                // Send the LED command
                sendLedCommandToEndpoint(endpt, controllerIndex);
                break;
            }
        }

        // Release the interface to allow xpad to take over again
        connection.releaseInterface(iface);
    }

    /**
     * Sets the player LED on every wireless slot, then reports failure on purpose so that
     * {@link UsbDriverService} closes the connection and the kernel driver resumes ownership.
     *
     * @return always false; see the class comment
     */
    @Override
    public boolean start() {
        int controllerIndex = 0;

        // On Android, there is a controller number associated with input devices.
        // We can use this to approximate the likely controller number. This won't
        // be completely accurate because there's no guarantee the order of interfaces
        // matches the order that devices were enumerated by xpad, but it's probably
        // better than nothing.
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice inputDev = InputDevice.getDevice(id);
            if (inputDev == null) {
                // Device was removed while looping
                continue;
            }

            // Newer xpad versions use a special product ID (0x02a1) for controllers
            // rather than copying the product ID of the dongle itself.
            if (inputDev.getVendorId() == device.getVendorId() &&
                    (inputDev.getProductId() == device.getProductId() ||
                            inputDev.getProductId() == 0x02a1) &&
                    inputDev.getControllerNumber() > 0) {
                controllerIndex = inputDev.getControllerNumber() - 1;
                break;
            }
        }

        // Send LED commands on the out endpoint of each interface. There is one interface
        // corresponding to each possible attached controller.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);

            // Skip the non-input interfaces
            if (iface.getInterfaceClass() != UsbConstants.USB_CLASS_VENDOR_SPEC ||
                    iface.getInterfaceSubclass() != XB360W_IFACE_SUBCLASS ||
                    iface.getInterfaceProtocol() != XB360W_IFACE_PROTOCOL) {
                continue;
            }

            sendLedCommandToInterface(iface, controllerIndex++);
        }

        // "Fail" to give control back to the kernel driver
        return false;
    }

    /** {@inheritDoc} Never called, since {@link #start()} always reports failure. */
    @Override
    public void stop() {
        // Nothing to do
    }

    /** {@inheritDoc} Unreachable: this class is never registered as a live controller. */
    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        // Unreachable.
    }

    /** {@inheritDoc} Unreachable: this class is never registered as a live controller. */
    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // Unreachable.
    }
}
