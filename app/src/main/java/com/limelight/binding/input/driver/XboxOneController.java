package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Driver for Xbox One and Series controllers over USB (the GIP protocol).
 *
 * <p>Unlike the 360 protocol, these controllers send nothing until they receive an
 * initialisation packet, and the packet they need varies by model — hence {@link #INIT_PKTS},
 * which is applied by VID/PID with a wildcard entry for the common case. They also send mode
 * reports that must be acknowledged, and offer trigger rumble in addition to the two main motors.
 */
public class XboxOneController extends AbstractXboxController {

    // Interface descriptor values that identify the GIP protocol
    private static final int XB1_IFACE_SUBCLASS = 71;
    private static final int XB1_IFACE_PROTOCOL = 208;

    private static final int[] SUPPORTED_VENDORS = {
            0x045e, // Microsoft
            0x0738, // Mad Catz
            0x0e6f, // Unknown
            0x0f0d, // Hori
            0x1532, // Razer Wildcat
            0x20d6, // PowerA
            0x24c6, // PowerA
            0x2dc8, // 8BitDo
            0x2e24, // Hyperkin
    };

    private static final byte[] FW2015_INIT = {0x05, 0x20, 0x00, 0x01, 0x00};
    private static final byte[] ONE_S_INIT = {0x05, 0x20, 0x00, 0x0f, 0x06};
    private static final byte[] HORI_INIT = {0x01, 0x20, 0x00, 0x09, 0x00, 0x04, 0x20, 0x3a,
            0x00, 0x00, 0x00, (byte)0x80, 0x00};
    private static final byte[] PDP_INIT1 = {0x0a, 0x20, 0x00, 0x03, 0x00, 0x01, 0x14};
    private static final byte[] PDP_INIT2 = {0x06, 0x20, 0x00, 0x02, 0x01, 0x00};
    private static final byte[] RUMBLE_INIT1 = {0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x1D, 0x1D, (byte)0xFF, 0x00, 0x00};
    private static final byte[] RUMBLE_INIT2 = {0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00};

    // Init sequences, applied in order to every device they match. A zero vendor or product ID
    // is a wildcard, so FW2015_INIT goes to everything and the model-specific entries add to it.
    private static InitPacket[] INIT_PKTS = {
            new InitPacket(0x0e6f, 0x0165, HORI_INIT),
            new InitPacket(0x0f0d, 0x0067, HORI_INIT),
            new InitPacket(0x0000, 0x0000, FW2015_INIT),
            new InitPacket(0x045e, 0x02ea, ONE_S_INIT),
            new InitPacket(0x045e, 0x02fe, ONE_S_INIT),
            new InitPacket(0x045e, 0x0b00, ONE_S_INIT),
            // Xbox Series S/X pads take the same init sequence as the One S
            new InitPacket(0x045e, 0x0b05, ONE_S_INIT),
            new InitPacket(0x045e, 0x0b12, ONE_S_INIT),
            new InitPacket(0x045e, 0x0b13, ONE_S_INIT),
            new InitPacket(0x0e6f, 0x0000, PDP_INIT1),
            new InitPacket(0x0e6f, 0x0000, PDP_INIT2),
            new InitPacket(0x24c6, 0x541a, RUMBLE_INIT1),
            new InitPacket(0x24c6, 0x542a, RUMBLE_INIT1),
            new InitPacket(0x24c6, 0x543a, RUMBLE_INIT1),
            new InitPacket(0x24c6, 0x541a, RUMBLE_INIT2),
            new InitPacket(0x24c6, 0x542a, RUMBLE_INIT2),
            new InitPacket(0x24c6, 0x543a, RUMBLE_INIT2),
    };

    // Sequence number stamped into every outgoing packet, shared by init and rumble
    private byte seqNum = 0;
    // Latest requested motor levels. All four are resent together, since the rumble packet
    // carries every motor and omitting one would stop it.
    private short lowFreqMotor = 0;
    private short highFreqMotor = 0;
    private short leftTriggerMotor = 0;
    private short rightTriggerMotor = 0;

    // Motor levels are a percentage, not a raw byte. MS-GIPUSB v20240916 section 3.1.5.6.1
    // (Direct Motor Command) specifies every level field as "Percentage, 0 - 100% (0x00 to
    // 0x64), of PWM for motor".
    private static final int RUMBLE_MAX_POWER = 100;

    /** {@inheritDoc} Advertises trigger rumble, which this generation adds. */
    public XboxOneController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
        capabilities |= MoonBridge.LI_CCAP_TRIGGER_RUMBLE;
    }

    /**
     * Parses the button, trigger and stick portion of a 0x20 input report. Triggers are 10-bit
     * and sticks are 16-bit signed, with Y inverted via {@code ~} so the extreme negative value
     * maps cleanly to full positive deflection.
     */
    private void processButtons(ByteBuffer buffer) {
        byte b = buffer.get();

        setButtonFlag(ControllerPacket.PLAY_FLAG, b & 0x04);
        setButtonFlag(ControllerPacket.BACK_FLAG, b & 0x08);

        setButtonFlag(ControllerPacket.A_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.B_FLAG, b & 0x20);
        setButtonFlag(ControllerPacket.X_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.Y_FLAG, b & 0x80);

        b = buffer.get();
        setButtonFlag(ControllerPacket.LEFT_FLAG, b & 0x04);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, b & 0x08);
        setButtonFlag(ControllerPacket.UP_FLAG, b & 0x01);
        setButtonFlag(ControllerPacket.DOWN_FLAG, b & 0x02);

        setButtonFlag(ControllerPacket.LB_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.RB_FLAG, b & 0x20);

        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b & 0x80);

        leftTrigger = buffer.getShort() / 1023.0f;
        rightTrigger = buffer.getShort() / 1023.0f;

        leftStickX = buffer.getShort() / 32767.0f;
        leftStickY = ~buffer.getShort() / 32767.0f;

        rightStickX = buffer.getShort() / 32767.0f;
        rightStickY = ~buffer.getShort() / 32767.0f;
    }

    /** Acknowledges a mode report, which the Xbox One S pad retransmits forever otherwise. */
    private void ackModeReport(byte seqNum) {
        byte[] payload = {0x01, 0x20, seqNum, 0x09, 0x00, 0x07, 0x20, 0x02,
                0x00, 0x00, 0x00, 0x00, 0x00};
        connection.bulkTransfer(outEndpt, payload, payload.length, 3000);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two report types matter: 0x20 carries the sticks and most buttons, while 0x07 carries
     * the Xbox guide button on its own.
     */
    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        switch (buffer.get())
        {
            case 0x20:
                if (buffer.remaining() < 17) {
                    LimeLog.severe("XBone button/axis read too small: "+buffer.remaining());
                    return false;
                }

                buffer.position(buffer.position()+3);
                processButtons(buffer);
                return true;

            case 0x07:
                if (buffer.remaining() < 4) {
                    LimeLog.severe("XBone mode read too small: "+buffer.remaining());
                    return false;
                }

                // The Xbox One S controller needs acks for mode reports otherwise
                // it retransmits them forever.
                if (buffer.get() == 0x30) {
                    ackModeReport(buffer.get());
                    buffer.position(buffer.position() + 1);
                }
                else {
                    buffer.position(buffer.position() + 2);
                }
                setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buffer.get() & 0x01);
                return true;
        }

        return false;
    }

    /** @return true if this device presents a GIP interface from a known vendor */
    public static boolean canClaimDevice(UsbDevice device) {
        for (int supportedVid : SUPPORTED_VENDORS) {
            if (device.getVendorId() == supportedVid &&
                    device.getInterfaceCount() >= 1 &&
                    device.getInterface(0).getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).getInterfaceSubclass() == XB1_IFACE_SUBCLASS &&
                    device.getInterface(0).getInterfaceProtocol() == XB1_IFACE_PROTOCOL) {
                return true;
            }
        }

        return false;
    }

    /** {@inheritDoc} Sends every init packet matching this device; without them it stays silent. */
    @Override
    protected boolean doInit() {
        // Send all applicable init packets
        for (InitPacket pkt : INIT_PKTS) {
            if (pkt.vendorId != 0 && device.getVendorId() != pkt.vendorId) {
                continue;
            }

            if (pkt.productId != 0 && device.getProductId() != pkt.productId) {
                continue;
            }

            byte[] data = Arrays.copyOf(pkt.data, pkt.data.length);

            // Populate sequence number
            data[2] = seqNum++;

            // Send the initialization packet
            int res = connection.bulkTransfer(outEndpt, data, data.length, 3000);
            if (res != data.length) {
                LimeLog.warning("Initialization transfer failed: "+res);
                return false;
            }
        }

        return true;
    }

    /**
     * Scales a 16-bit rumble magnitude onto the protocol's 0 - 100 range.
     *
     * <p>The mask is load-bearing: {@code short} is signed in Java, so any magnitude at or above
     * half strength arrives negative. The previous {@code >> 9} sign-extended it, which sent 192
     * for half strength and 255 for full where the maximum is 100 - and left a discontinuity at
     * the midpoint, climbing to 63 across the bottom half before jumping to 192.
     */
    private static byte scaleMotor(short magnitude) {
        return (byte)(((magnitude & 0xFFFF) * RUMBLE_MAX_POWER) / 0xFFFF);
    }

    /**
     * Sends all four motor levels, followed by the fixed on/off/repeat envelope.
     */
    private void sendRumblePacket() {
        byte[] data = {
                0x09, 0x00, seqNum++, 0x09, 0x00,
                0x0F,
                scaleMotor(leftTriggerMotor),
                scaleMotor(rightTriggerMotor),
                scaleMotor(lowFreqMotor),
                scaleMotor(highFreqMotor),
                (byte)0xFF, 0x00, (byte)0xFF
        };
        int res = connection.bulkTransfer(outEndpt, data, data.length, 100);
        if (res != data.length) {
            LimeLog.warning("Rumble transfer failed: "+res);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        this.lowFreqMotor = lowFreqMotor;
        this.highFreqMotor = highFreqMotor;
        sendRumblePacket();
    }

    /** {@inheritDoc} */
    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        this.leftTriggerMotor = leftTrigger;
        this.rightTriggerMotor = rightTrigger;
        sendRumblePacket();
    }

    /** One init sequence and the device it applies to; a zero ID matches any device. */
    private static class InitPacket {
        final int vendorId;
        final int productId;
        final byte[] data;

        InitPacket(int vendorId, int productId, byte[] data) {
            this.vendorId = vendorId;
            this.productId = productId;
            this.data = data;
        }
    }
}
