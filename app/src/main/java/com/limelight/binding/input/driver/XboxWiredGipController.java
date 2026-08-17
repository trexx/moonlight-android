package com.limelight.binding.input.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;

/**
 * An Xbox pad on a USB cable, driven through the full GIP stack rather than {@link
 * XboxOneController}.
 *
 * <p>The point of it is headphone audio. MS-GIPUSB 2.2.1.4 only enumerates the audio sub-device
 * after the security handshake completes, and {@link XboxOneController} has no handshake — it sends
 * canned init packets and parses two message types. Moving a cabled pad onto {@link GipController}
 * gives it the handshake, metadata, chunk reassembly and the device-state machine that the wireless
 * path already has, all of which the audio path needs before a single sample can be sent.
 *
 * <p><b>This replaces a working input path, so it is off unless asked for.</b> Android drives a
 * cabled Xbox pad perfectly well on its own; taking it over gains audio and risks input, which is
 * the wrong trade to make on a user's behalf. See {@code UsbDriverService.shouldClaimDevice}.
 *
 * <p>Construction is a factory rather than a constructor because the native transport has to exist
 * before the {@link GipController} that wraps it: the superclass takes the native controller handle
 * as an argument, and that handle only exists once the transport has claimed the device.
 */
public class XboxWiredGipController extends GipController {
    // Native transport instance, or 0 once destroyed
    private long wiredHandle;

    private final UsbDeviceConnection connection;

    static {
        System.loadLibrary("xow-driver");
    }

    private XboxWiredGipController(UsbDevice device, UsbDeviceConnection connection, int deviceId,
                                   UsbDriverListener listener, long wiredHandle, long gipHandle) {
        super(deviceId, listener, device.getVendorId(), device.getProductId(), gipHandle);

        this.wiredHandle = wiredHandle;
        this.connection = connection;
    }

    /**
     * Claims the pad and brings the GIP stack up on it.
     *
     * @return the controller, or null if the device could not be claimed — in which case nothing
     *         has been left running and the caller still owns {@code connection}
     */
    public static XboxWiredGipController create(UsbDevice device, UsbDeviceConnection connection,
                                                int deviceId, UsbDriverListener listener) {
        long wired = createWiredDriver(connection.getFileDescriptor());
        if (wired == 0) {
            LimeLog.warning("Wired GIP: could not create the native transport");
            return null;
        }

        // Every failure past this point has to destroy the transport, or the claimed interface and
        // the read thread outlive the attempt.
        if (!startWiredDriver(wired)) {
            LimeLog.warning("Wired GIP: transport failed to start");
            destroyWiredDriver(wired);
            return null;
        }

        long gip = wiredControllerHandle(wired);
        if (gip == 0) {
            LimeLog.warning("Wired GIP: transport started without a controller");
            destroyWiredDriver(wired);
            return null;
        }

        return new XboxWiredGipController(device, connection, deviceId, listener, wired, gip);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The transport is already running by the time this object exists, so this only announces
     * the pad. That happens here rather than when the GIP device finishes initialising, because
     * initialisation is asynchronous - announce, metadata, then start - and the host would
     * otherwise learn about the pad seconds after it was plugged in. It reports neutral input
     * until the device is up, which is what an idle pad reports anyway.
     */
    @Override
    public boolean start() {
        notifyDeviceAdded();

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unlike the wireless path, teardown belongs here: there is no dongle above this to own the
     * device. Idempotent, and the handle is cleared before anything else can fail so a second call
     * cannot reach into freed memory.
     */
    @Override
    public void stop() {
        if (wiredHandle == 0) {
            return;
        }

        long handle = wiredHandle;
        wiredHandle = 0;

        destroyWiredDriver(handle);
        connection.close();

        // Tells the service to drop us, which is also what takes this pad out of PadAudioSink
        // before the native controller behind it is gone.
        notifyDeviceRemoved();
    }

    /**
     * @return whether this device is a cabled GIP pad. The same descriptor test
     *         {@link XboxOneController} uses, because it is the same hardware — what differs is
     *         which driver we put on it.
     */
    public static boolean canClaimDevice(UsbDevice device) {
        return XboxOneController.canClaimDevice(device);
    }

    private static native long createWiredDriver(int fd);
    private static native boolean startWiredDriver(long handle);
    private static native long wiredControllerHandle(long handle);
    private static native void destroyWiredDriver(long handle);
}
