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

/**
 * Shared plumbing for the Xbox controller families: claiming the device, locating its bulk
 * endpoints, and running the reader loop.
 *
 * <p>Subclasses supply only the parts that differ between generations — {@link #doInit()} for any
 * handshake the device needs, {@link #handleRead(ByteBuffer)} to parse its report format, and
 * their own rumble encoding.
 *
 * <p>The supported button set is fixed in the constructor rather than probed, since these
 * controllers all expose the same buttons and only differ in how they encode them.
 */
public abstract class AbstractXboxController extends AbstractController {
    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    private Thread inputThread;
    private boolean stopped;

    // Bulk endpoints located by start(); input reports arrive on inEndpt and rumble goes out
    // on outEndpt
    protected UsbEndpoint inEndpt, outEndpt;

    /**
     * The buttons every Xbox generation exposes, advertised to the host so it knows what this pad
     * can send. Shared with {@link XboxWirelessController}, which reaches the same hardware through
     * the wireless adapter's native driver rather than this class.
     */
    static final int XBOX_BUTTON_FLAGS =
            ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                    ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                    ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                    ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                    ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG;

    /** Declares the button set and rumble support common to every Xbox controller generation. */
    public AbstractXboxController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE;
        // supportedButtonFlags, not buttonFlags: the latter is live state, reported to the host on
        // every input report. Seeding it with the whole set claimed every button was held until the
        // first report arrived, and left getSupportedButtonFlags() returning zero - so the host was
        // told this pad has no buttons at all.
        this.supportedButtonFlags = XBOX_BUTTON_FLAGS;
    }

    /**
     * Builds the reader thread: announce the controller, then loop parsing input reports until
     * stopped or the device disappears.
     */
    private Thread createInputThread() {
        return new Thread() {
            public void run() {
                try {
                    // Delay for a moment before reporting the new gamepad and
                    // accepting new input. This allows time for the old InputDevice
                    // to go away before we reclaim its spot. If the old device is still
                    // around when we call notifyDeviceAdded(), we won't be able to claim
                    // the controller number used by the original InputDevice.
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }

                // Report that we're added _before_ reporting input
                notifyDeviceAdded();

                while (!isInterrupted() && !stopped) {
                    byte[] buffer = new byte[64];

                    int res;

                    //
                    // There's no way that I can tell to determine if a device has failed
                    // or if the timeout has simply expired. We'll check how long the transfer
                    // took to fail and assume the device failed if it happened before the timeout
                    // expired.
                    //

                    do {
                        // Read the next input state packet
                        long lastMillis = SystemClock.uptimeMillis();
                        res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);

                        // If we get a zero length response, treat it as an error
                        if (res == 0) {
                            res = -1;
                        }

                        if (res == -1 && SystemClock.uptimeMillis() - lastMillis < 1000) {
                            LimeLog.warning("Detected device I/O error");
                            AbstractXboxController.this.stop();
                            break;
                        }
                    } while (res == -1 && !isInterrupted() && !stopped);

                    if (res == -1 || stopped) {
                        break;
                    }

                    if (handleRead(ByteBuffer.wrap(buffer, 0, res).order(ByteOrder.LITTLE_ENDIAN))) {
                        // Report input if handleRead() returns true
                        reportInput();
                    }
                }
            }
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims the interfaces, locates exactly one endpoint in each direction, runs the
     * subclass's initialisation and only then starts reading. Duplicate endpoints are treated as
     * a failure rather than guessed at, since picking the wrong one yields a controller that
     * appears connected but never reports.
     */
    public boolean start() {
        // Force claim all interfaces
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);

            if (!connection.claimInterface(iface, true)) {
                LimeLog.warning("Failed to claim interfaces");
                return false;
            }
        }

        // Find the endpoints
        UsbInterface iface = device.getInterface(0);
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getDirection() == UsbConstants.USB_DIR_IN) {
                if (inEndpt != null) {
                    LimeLog.warning("Found duplicate IN endpoint");
                    return false;
                }
                inEndpt = endpt;
            }
            else if (endpt.getDirection() == UsbConstants.USB_DIR_OUT) {
                if (outEndpt != null) {
                    LimeLog.warning("Found duplicate OUT endpoint");
                    return false;
                }
                outEndpt = endpt;
            }
        }

        // Make sure the required endpoints were present
        if (inEndpt == null || outEndpt == null) {
            LimeLog.warning("Missing required endpoint");
            return false;
        }

        // Run the init function
        if (!doInit()) {
            return false;
        }

        // Start listening for controller input
        inputThread = createInputThread();
        inputThread.start();

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reachable both from the service and from the reader thread's own error path, so the
     * {@code stopped} guard is what keeps the teardown from running twice.
     */
    public void stop() {
        if (stopped) {
            return;
        }

        stopped = true;

        // Cancel any rumble effects
        rumble((short)0, (short)0);

        // Stop the input thread
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }

        // Release the interfaces we claimed, otherwise Android's own driver
        // cannot take the device back over when it is reattached.
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            connection.releaseInterface(device.getInterface(i));
        }

        // Close the USB connection
        connection.close();

        // Report the device removed
        notifyDeviceRemoved();
    }

    /**
     * Parses one input report into the inherited state fields.
     *
     * @param buffer the report, little-endian, positioned at its start
     * @return true if this report updated the controller state and should be reported onward;
     *         false for reports of other types, which are ignored
     */
    protected abstract boolean handleRead(ByteBuffer buffer);

    /**
     * Performs any handshake the device needs before it will send input reports.
     *
     * @return false to abort the claim
     */
    protected abstract boolean doInit();
}
