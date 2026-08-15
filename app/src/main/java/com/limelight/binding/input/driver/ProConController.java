package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Driver for the Nintendo Switch Pro Controller over USB.
 *
 * Android's built-in hid-nintendo driver handles this controller's buttons and sticks, but it
 * doesn't expose the motion sensors. Claiming the device ourselves lets us report gyro and
 * accelerometer data (and drive rumble) to the host.
 *
 * <p>The protocol is Nintendo's undocumented one, reverse engineered by the community and
 * documented at <a href="https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering">
 * dekuNukem/Nintendo_Switch_Reverse_Engineering</a>, which is where the command IDs, report
 * layouts and SPI flash addresses below come from. Two command families are involved: bare
 * handshake commands (report 0x80) that set up the USB link, and subcommands (report 0x01) that
 * configure the controller and read its flash.
 *
 * <p>Everything happens on the reader thread created by {@link #start()}: initialisation runs
 * first, then the thread loops reading 0x30 input reports until stopped. Rumble is the exception,
 * arriving from the host's callback thread and writing to the same endpoint.
 */
public class ProConController extends AbstractController {

    // Every report to and from this controller is a fixed 64 bytes
    private static final int PACKET_SIZE = 64;
    // Stick calibration lives in the controller's SPI flash. The factory pair is always present;
    // the user pair is only valid when preceded by the 0xB2 0xA1 magic, written when someone runs
    // the Switch's own stick calibration.
    private static final int FACTORY_LS_CALIBRATION_OFFSET = 0x603D;
    private static final int FACTORY_RS_CALIBRATION_OFFSET = 0x6046;
    private static final int USER_LS_MAGIC_OFFSET = 0x8010;
    private static final int USER_LS_CALIBRATION_OFFSET = 0x8012;
    private static final int USER_RS_MAGIC_OFFSET = 0x801B;
    private static final int USER_RS_CALIBRATION_OFFSET = 0x801D;
    // Three 12-bit pairs per stick, packed into 9 bytes
    private static final int STICK_CALIBRATION_LENGTH = 9;
    // Initialisation commands are unreliable while the controller is still settling, so each one
    // is retried rather than failing the whole handshake
    private static final int COMMAND_RETRIES = 10;

    // The accelerometer reports at 1/4096 g per LSB, but the protocol wants m/s^2.
    private static final float ACCEL_LSB_TO_MS2 = 9.80665f / 4096.0f;

    // The gyroscope reports at approximately 1/16 deg/s per LSB, which is what the protocol wants.
    private static final float GYRO_LSB_TO_DPS = 1.0f / 16.0f;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private UsbEndpoint inEndpt, outEndpt;
    private Thread inputThread;
    private boolean stopped = false;
    // Rolling 4-bit sequence number the controller expects on every outgoing report
    private byte sendPacketCount = 0;
    private final int[][][] stickCalibration = new int[2][2][3]; // [stick][axis][min, center, max]
    private final float[][][] stickExtends = new float[2][2][2]; // Pre-calculated scale for each axis

    /** @return true if this is a Nintendo (0x057e) Switch Pro Controller (0x2009) */
    public static boolean canClaimDevice(UsbDevice device) {
        return device.getVendorId() == 0x057e && device.getProductId() == 0x2009;
    }

    /** Declares gyro, accelerometer and rumble support, which is the reason for claiming this pad. */
    public ProConController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = MoonBridge.LI_CTYPE_NINTENDO;
        this.capabilities = MoonBridge.LI_CCAP_GYRO | MoonBridge.LI_CCAP_ACCEL | MoonBridge.LI_CCAP_RUMBLE;
    }

    /**
     * Builds the reader thread: initialise the controller, announce it, then loop on the input
     * endpoint until stopped. The initialisation order matters — the handshake has to precede
     * everything, and the input report mode has to be set before 0x30 reports start arriving.
     */
    private Thread createInputThread() {
        return new Thread(() -> {
            // The controller isn't ready to answer commands the instant it enumerates.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }

            if (!handshake()) {
                LimeLog.warning("ProCon: Initial handshake failed!");
                ProConController.this.stop();
                return;
            }

            LimeLog.info("ProCon: highspeed " + highSpeed());
            LimeLog.info("ProCon: handshake " + handshake());
            LimeLog.info("ProCon: loadstickcalibration " + loadStickCalibration());
            LimeLog.info("ProCon: enablevibration " + enableVibration(true));
            LimeLog.info("ProCon: setinputreportmode " + setInputReportMode((byte) 0x30));
            LimeLog.info("ProCon: forceusb " + forceUSB());
            LimeLog.info("ProCon: setplayerled " + setPlayerLED(getControllerId() + 1));
            LimeLog.info("ProCon: enableimu " + enableIMU(true));

            LimeLog.info("ProCon: initialized!");

            notifyDeviceAdded();

            // Both hoisted out of the loop below. This runs at roughly 120 Hz per controller, and
            // a fresh byte[] plus a wrapper object on every read is the only allocation on a path
            // that otherwise has none — handleRead() itself allocates nothing.
            byte[] buffer = new byte[PACKET_SIZE];
            ByteBuffer bufferView = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);

            // Resolved once rather than on both loop conditions below: this loop turns over at
            // roughly 120 Hz and the thread it runs on obviously does not change.
            final Thread readThread = Thread.currentThread();

            while (!readThread.isInterrupted() && !stopped) {
                int res;
                do {
                    long lastMillis = SystemClock.uptimeMillis();
                    res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 1000);
                    if (res == 0) {
                        res = -1;
                    }
                    // A failure that came back faster than the timeout is a real I/O error
                    // (usually the cable being pulled); one that took the full second is just
                    // an idle controller with nothing to report, so keep waiting
                    if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                        LimeLog.warning("Detected device I/O error");
                        ProConController.this.stop();
                        break;
                    }
                } while (res == -1 && !readThread.isInterrupted() && !stopped);

                if (res == -1 || stopped) {
                    break;
                }

                // Re-window the view over however much this read produced, matching what
                // wrap(buffer, 0, res) used to give: position 0, limit res. Byte order is set
                // once at construction and survives, since neither call touches it.
                bufferView.clear();
                bufferView.limit(res);

                if (handleRead(bufferView)) {
                    reportInput();
                    reportMotion();
                }
            }
        });
    }

    /** @return true if the whole buffer reached the controller */
    private boolean sendData(byte[] data, int size) {
        return connection.bulkTransfer(outEndpt, data, size, 100) == size;
    }

    /**
     * Sends a bare 0x80 handshake command.
     *
     * @param id        command byte (0x02 handshake, 0x03 baud rate, 0x04 disable USB timeout)
     * @param waitReply wait for the matching 0x81 acknowledgement before returning
     * @return true if the command was sent and, when requested, acknowledged
     */
    private boolean sendCommand(byte id, boolean waitReply) {
        byte[] data = new byte[] {(byte) 0x80, id};
        for (int i = 0; i < COMMAND_RETRIES; i++) {
            if (!sendData(data, data.length)) {
                continue;
            }
            if (!waitReply) {
                return true;
            }

            byte[] buffer = new byte[PACKET_SIZE];
            int res;
            int retries = 0;
            do {
                res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 100);
                if (res > 0 && (buffer[0] & 0xFF) == 0x81 && (buffer[1] & 0xFF) == id) {
                    return true;
                }
                retries += 1;
            } while (retries < 20 && res > 0 && !Thread.currentThread().isInterrupted() && !stopped);
        }

        return false;
    }

    /**
     * Sends a 0x01 subcommand and waits for its 0x21 reply.
     *
     * <p>The reply is matched by echoed subcommand ID at byte 14, and any subcommand payload
     * (such as SPI flash contents) starts at byte 20 of {@code buffer}.
     *
     * @param payload subcommand arguments, placed after the 11-byte header
     * @param buffer  scratch buffer of {@link #PACKET_SIZE} bytes that receives the reply
     * @return true if a matching reply arrived
     */
    private boolean sendSubcommand(byte subcommand, byte[] payload, byte[] buffer) {
        byte[] data = new byte[11 + payload.length];
        data[0] = 0x01;  // Rumble and subcommand
        data[10] = subcommand;
        System.arraycopy(payload, 0, data, 11, payload.length);

        for (int i = 0; i < COMMAND_RETRIES; i++) {
            // The counter advances on every packet we put on the wire, resends included
            data[1] = sendPacketCount++;
            if (sendPacketCount > 0xF) {
                sendPacketCount = 0;
            }

            if (!sendData(data, data.length)) {
                continue;
            }

            // Wait for response
            int res;
            int retries = 0;
            do {
                res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 100);
                if (res < 0 || buffer[0] != 0x21 || buffer[14] != subcommand) {
                    retries += 1;
                } else {
                    return true;
                }
            } while (retries < 20 && res > 0 && !Thread.currentThread().isInterrupted() && !stopped);

            LimeLog.warning("ProCon: Failed to get subcmd reply (attempt " + (i + 1) + "): " +
                    res + " bytes received, " +
                    String.format((Locale) null, "0x%02x, 0x%02x", buffer[0], buffer[14]));

            // Give up early rather than burning the remaining attempts on a controller that is
            // going away; otherwise fall through and resend.
            if (Thread.currentThread().isInterrupted() || stopped) {
                break;
            }
        }

        return false;
    }

    /** Requests the controller's MAC and device type, which also brings up the USB link. */
    private boolean handshake() {
        return sendCommand((byte) 0x02, true);
    }

    /** Switches the link to 3 Mbit baud. Requires a second {@link #handshake()} afterwards. */
    private boolean highSpeed() {
        return sendCommand((byte) 0x03, true);
    }

    /**
     * Disables the controller's USB timeout, which would otherwise drop it back to Bluetooth
     * when the host stops polling.
     */
    private boolean forceUSB() {
        return sendCommand((byte) 0x04, true);
    }

    /**
     * @param mode report mode; 0x30 is the full report carrying sticks, buttons and motion,
     *             as opposed to the cut-down default that omits the sensors
     */
    private boolean setInputReportMode(byte mode) {
        final byte[] data = new byte[] {mode};
        return sendSubcommand((byte) 0x03, data, new byte[PACKET_SIZE]);
    }

    /** @param id player number as a bitmask of the four front LEDs */
    private boolean setPlayerLED(int id) {
        final byte[] data = new byte[] {(byte) (id & 0b1111)};
        return sendSubcommand((byte) 0x30, data, new byte[PACKET_SIZE]);
    }

    /** Enables the gyro and accelerometer, which are off until asked for. */
    private boolean enableIMU(boolean enable) {
        byte[] data = new byte[] {(byte) (enable ? 0x01 : 0x00)};
        return sendSubcommand((byte) 0x40, data, new byte[PACKET_SIZE]);
    }

    /** Enables the rumble motors, without which {@link #rumble} is ignored. */
    private boolean enableVibration(boolean enable) {
        byte[] data = new byte[] {(byte) (enable ? 0x01 : 0x00)};
        return sendSubcommand((byte) 0x48, data, new byte[PACKET_SIZE]);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims every interface and locates the bulk endpoints. Initialisation itself is left to
     * the reader thread, because the controller needs a moment after enumeration before it will
     * answer commands.
     */
    @Override
    public boolean start() {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (!connection.claimInterface(iface, true)) {
                LimeLog.warning("Failed to claim interfaces");
                return false;
            }
        }

        UsbInterface iface = device.getInterface(0);
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_IN) {
                inEndpt = endpt;
            } else if (endpt.getDirection() == UsbConstants.USB_DIR_OUT) {
                outEndpt = endpt;
            }
        }

        if (inEndpt == null || outEndpt == null) {
            LimeLog.warning("Missing required endpoint");
            return false;
        }

        inputThread = createInputThread();
        inputThread.start();

        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void stop() {
        if (stopped) {
            return;
        }

        stopped = true;

        // Cancel any rumble effects
        rumble((short) 0, (short) 0);

        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }

        // Release the interfaces we claimed, otherwise Android's own driver
        // cannot take the device back over when it is reattached.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            connection.releaseInterface(device.getInterface(i));
        }

        connection.close();
        notifyDeviceRemoved();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Encodes the two amplitudes into the controller's packed frequency/amplitude rumble
     * format and sends a bare 0x10 rumble report, which needs no reply. Zero amplitudes are
     * left as the zero-filled default, which the controller reads as "motor off".
     */
    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        byte[] data = new byte[10];
        data[0] = 0x10;  // Rumble command
        data[1] = sendPacketCount++;  // Counter (increments per call)
        if (sendPacketCount > 0xF) {
            sendPacketCount = 0;
        }

        if (lowFreqMotor != 0) {
            // Frequency from the top 4 bits of the amplitude, amplitude from the top 8.
            // NB the inner parentheses: >> binds tighter than &, so without them this would mask
            // the low nibble instead of shifting the high one down.
            data[4] = data[8] = (byte) (0x50 - ((lowFreqMotor & 0xFFFF) >> 12));
            data[5] = data[9] = (byte) ((((lowFreqMotor & 0xFFFF) >> 8) / 5) + 0x40);
        }
        if (highFreqMotor != 0) {
            data[6] = (byte) ((0x70 - ((highFreqMotor & 0xFFFF) >> 10) & -0x04));
            data[7] = (byte) (((highFreqMotor & 0xFFFF) >> 8) * 0xC8 / 0xFF);
        }

        // Fixed bits the controller expects in every rumble report. Bytes 2 and 6 carry none, so
        // they keep the zero the array was allocated with.
        data[3] |= 0x01;
        data[5] |= 0x40;
        data[7] |= 0x01;
        data[9] |= 0x40;

        sendData(data, data.length);
    }

    /** {@inheritDoc} */
    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // ProCon does not support trigger-specific rumble
    }

    /**
     * Parses a 0x30 full input report into the inherited state fields.
     *
     * <p>Byte offsets are fixed by the protocol: 3 to 5 are the button bitmaps, 6 to 11 pack the
     * four 12-bit stick axes, and 37 onwards carry three sets of IMU samples (only the first is
     * used, since the host wants one sample per report rather than the controller's 5 ms
     * history). Stick Y axes are negated and offset by one because the controller reports Y
     * increasing downwards.
     *
     * @return true if this was a full report and the state was updated
     */
    protected boolean handleRead(ByteBuffer buffer) {
        if (buffer.remaining() < PACKET_SIZE) {
            return false;
        }

        // Only the full input report (0x30) carries stick and motion data
        if (buffer.get(0) != 0x30) {
            return false;
        }

        buttonFlags = 0;
        // Nintendo's face button layout is swapped relative to the standard mapping
        setButtonFlag(ControllerPacket.B_FLAG, buffer.get(3) & 0x08);
        setButtonFlag(ControllerPacket.A_FLAG, buffer.get(3) & 0x04);
        setButtonFlag(ControllerPacket.Y_FLAG, buffer.get(3) & 0x02);
        setButtonFlag(ControllerPacket.X_FLAG, buffer.get(3) & 0x01);
        setButtonFlag(ControllerPacket.UP_FLAG, buffer.get(5) & 0x02);
        setButtonFlag(ControllerPacket.DOWN_FLAG, buffer.get(5) & 0x01);
        setButtonFlag(ControllerPacket.LEFT_FLAG, buffer.get(5) & 0x08);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, buffer.get(5) & 0x04);
        setButtonFlag(ControllerPacket.BACK_FLAG, buffer.get(4) & 0x01);
        setButtonFlag(ControllerPacket.PLAY_FLAG, buffer.get(4) & 0x02);
        setButtonFlag(ControllerPacket.MISC_FLAG, buffer.get(4) & 0x20); // Screenshot
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, buffer.get(4) & 0x10); // Home
        setButtonFlag(ControllerPacket.LB_FLAG, buffer.get(5) & 0x40);
        setButtonFlag(ControllerPacket.RB_FLAG, buffer.get(3) & 0x40);
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, buffer.get(4) & 0x08);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, buffer.get(4) & 0x04);

        leftTrigger = ((buffer.get(5) & 0x80) != 0) ? 1 : 0;
        rightTrigger = ((buffer.get(3) & 0x80) != 0) ? 1 : 0;

        int rawLeftStickX = buffer.get(6) & 0xFF | ((buffer.get(7) & 0x0F) << 8);
        int rawLeftStickY = ((buffer.get(7) & 0xF0) >> 4) | (buffer.get(8) << 4);
        int rawRightStickX = buffer.get(9) & 0xFF | ((buffer.get(10) & 0x0F) << 8);
        int rawRightStickY = ((buffer.get(10) & 0xF0) >> 4) | (buffer.get(11) << 4);

        leftStickX = applyStickCalibration(rawLeftStickX, 0, 0);
        leftStickY = applyStickCalibration(-rawLeftStickY - 1, 0, 1);
        rightStickX = applyStickCalibration(rawRightStickX, 1, 0);
        rightStickY = applyStickCalibration(-rawRightStickY - 1, 1, 1);

        accelX = buffer.getShort(37) * ACCEL_LSB_TO_MS2;
        accelY = buffer.getShort(39) * ACCEL_LSB_TO_MS2;
        accelZ = buffer.getShort(41) * ACCEL_LSB_TO_MS2;
        gyroZ = -buffer.getShort(43) * GYRO_LSB_TO_DPS;
        gyroX = -buffer.getShort(45) * GYRO_LSB_TO_DPS;
        gyroY = buffer.getShort(47) * GYRO_LSB_TO_DPS;

        return true;
    }

    /**
     * Reads from the controller's internal SPI flash via subcommand 0x10.
     *
     * @param buffer receives the reply; the flash contents begin at byte 20
     * @return true if the read succeeded
     */
    private boolean spiFlashRead(int offset, int length, byte[] buffer) {
        // SPI Read Address (Little Endian)
        byte[] address = {
                (byte) (offset & 0xFF),
                (byte) ((offset >> 8) & 0xFF),
                (byte) ((offset >> 16) & 0xFF),
                (byte) ((offset >> 24) & 0xFF),
                (byte) length
        };

        if (!sendSubcommand((byte) 0x10, address, buffer)) {
            LimeLog.warning("ProCon: Failed to receive SPI Flash data.");
            return false;
        }

        return true;
    }

    /**
     * @return true if the 0xB2 0xA1 magic is present, meaning the user calibration that follows
     *         it has actually been written and isn't just erased flash
     */
    private boolean checkUserCalMagic(int offset) {
        byte[] buffer = new byte[PACKET_SIZE];

        if (!spiFlashRead(offset, 2, buffer)) {
            return false;
        }

        return ((buffer[20] & 0xFF) == 0xB2) && ((buffer[21] & 0xFF) == 0xA1);
    }

    /**
     * Loads both sticks' calibration from flash, preferring the user's own over the factory
     * values, and falls back to nominal 12-bit ranges for any stick that can't be read.
     *
     * <p>The two sticks store their three 12-bit pairs in a different order, which is why the
     * unpacking below is duplicated rather than shared.
     *
     * @return always true; a failed read degrades to the default calibration rather than
     *         failing initialisation, since an uncalibrated stick still works
     */
    private boolean loadStickCalibration() {
        byte[] buffer = new byte[PACKET_SIZE];

        int lsAddr = FACTORY_LS_CALIBRATION_OFFSET;
        int rsAddr = FACTORY_RS_CALIBRATION_OFFSET;

        // Prefer the user's own calibration if they've run the Switch's stick calibration
        if (checkUserCalMagic(USER_LS_MAGIC_OFFSET)) {
            lsAddr = USER_LS_CALIBRATION_OFFSET;
            LimeLog.info("ProCon: LS has user calibration!");
        }
        if (checkUserCalMagic(USER_RS_MAGIC_OFFSET)) {
            rsAddr = USER_RS_CALIBRATION_OFFSET;
            LimeLog.info("ProCon: RS has user calibration!");
        }

        boolean lsCalibrated = false;
        if (spiFlashRead(lsAddr, STICK_CALIBRATION_LENGTH, buffer)) {
            // The left stick stores max first, then center, then min
            int xMax = (buffer[20] & 0xFF) | ((buffer[21] & 0x0F) << 8);
            int yMax = ((buffer[21] & 0xF0) >> 4) | ((buffer[22] & 0xFF) << 4);
            int xCenter = (buffer[23] & 0xFF) | ((buffer[24] & 0x0F) << 8);
            int yCenter = ((buffer[24] & 0xF0) >> 4) | ((buffer[25] & 0xFF) << 4);
            int xMin = (buffer[26] & 0xFF) | ((buffer[27] & 0x0F) << 8);
            int yMin = ((buffer[27] & 0xF0) >> 4) | ((buffer[28] & 0xFF) << 4);
            applyCalibration(0, xMin, xCenter, xMax, yMin, yCenter, yMax);

            lsCalibrated = true;
        }

        if (!lsCalibrated) {
            applyDefaultCalibration(0);
        }

        boolean rsCalibrated = false;
        if (spiFlashRead(rsAddr, STICK_CALIBRATION_LENGTH, buffer)) {
            // The right stick stores center first, then min, then max
            int xCenter = (buffer[20] & 0xFF) | ((buffer[21] & 0x0F) << 8);
            int yCenter = ((buffer[21] & 0xF0) >> 4) | ((buffer[22] & 0xFF) << 4);
            int xMin = (buffer[23] & 0xFF) | ((buffer[24] & 0x0F) << 8);
            int yMin = ((buffer[24] & 0xF0) >> 4) | ((buffer[25] & 0xFF) << 4);
            int xMax = (buffer[26] & 0xFF) | ((buffer[27] & 0x0F) << 8);
            int yMax = ((buffer[27] & 0xF0) >> 4) | ((buffer[28] & 0xFF) << 4);
            applyCalibration(1, xMin, xCenter, xMax, yMin, yCenter, yMax);

            rsCalibrated = true;
        }

        if (!rsCalibrated) {
            applyDefaultCalibration(1);
        }

        return true;
    }

    /**
     * Converts one stick's raw calibration triple into absolute bounds and usable extents.
     *
     * <p>The flash values are deltas from centre rather than absolute positions, and the Y axis
     * is stored inverted relative to the direction {@link #handleRead} produces, hence the
     * subtractions from 0x1000.
     */
    private void applyCalibration(int stick, int xMin, int xCenter, int xMax, int yMin, int yCenter, int yMax) {
        stickCalibration[stick][0][0] = xCenter - xMin;
        stickCalibration[stick][0][1] = xCenter;
        stickCalibration[stick][0][2] = xCenter + xMax;
        stickCalibration[stick][1][0] = 0x1000 - yCenter - yMax;
        stickCalibration[stick][1][1] = 0x1000 - yCenter;
        stickCalibration[stick][1][2] = 0x1000 - yCenter + yMin;

        // Start the usable range at 70% of the calibrated extent. applyStickCalibration()
        // widens these if it ever sees a larger deflection, so a conservative start just
        // means full range is reached after the stick has been pushed to its corners once.
        stickExtends[stick][0][0] = (float) ((xCenter - stickCalibration[stick][0][0]) * -0.7);
        stickExtends[stick][0][1] = (float) ((stickCalibration[stick][0][2] - xCenter) * 0.7);
        stickExtends[stick][1][0] = (float) ((yCenter - stickCalibration[stick][1][0]) * -0.7);
        stickExtends[stick][1][1] = (float) ((stickCalibration[stick][1][2] - yCenter) * 0.7);
    }

    /** Nominal full-scale 12-bit calibration, used when flash can't be read. */
    private void applyDefaultCalibration(int stick) {
        for (int axis = 0; axis < 2; axis++) {
            stickCalibration[stick][axis][0] = 0x000;  // Min
            stickCalibration[stick][axis][1] = 0x800;  // Center
            stickCalibration[stick][axis][2] = 0xFFF;  // Max

            stickExtends[stick][axis][0] = -0x700;
            stickExtends[stick][axis][1] = 0x700;
        }
    }

    /**
     * Normalises a raw axis reading to -1.0 to 1.0 using the loaded calibration.
     *
     * <p>Self-widening: a reading beyond the current extent becomes the new extent and reports
     * full deflection. That absorbs sticks whose real range differs from what flash claims, at
     * the cost of the range only being fully learned once the stick has been pushed to its
     * corners. It also means the extents are per-session and never shrink back.
     *
     * @param stick 0 left, 1 right
     * @param axis  0 X, 1 Y
     */
    private float applyStickCalibration(int value, int stick, int axis) {
        int center = stickCalibration[stick][axis][1];

        // handleRead() negates the Y axes, so wrap them back into the unsigned 12-bit range
        if (value < 0) {
            value += 0x1000;
        }

        value -= center;

        if (value < stickExtends[stick][axis][0]) {
            stickExtends[stick][axis][0] = value;
            return -1;
        } else if (value > stickExtends[stick][axis][1]) {
            stickExtends[stick][axis][1] = value;
            return 1;
        }

        if (value > 0) {
            return value / stickExtends[stick][axis][1];
        } else {
            return -value / stickExtends[stick][axis][0];
        }
    }
}
