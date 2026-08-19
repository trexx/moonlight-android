package com.limelight.binding.input.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;

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
    // Interface 0 carries every GIP message; MS-GIPUSB 2.2.12 puts audio on interface 1
    private static final int GIP_INTERFACE = 0;

    // Native transport instance, or 0 once destroyed
    private long wiredHandle;

    private final UsbDeviceConnection connection;
    private final UsbInterface gipInterface;

    /*
     * Something to recognise this pad by after it has been re-enumerated. The device name is a bus
     * path and changes every time; the serial does not. Read once here, while permission is
     * certainly held, rather than from a UsbDevice we would otherwise have to keep alive.
     */
    private final String serial;

    static {
        System.loadLibrary("xow-driver");
    }

    private XboxWiredGipController(UsbDevice device, UsbDeviceConnection connection,
                                   UsbInterface gipInterface, int deviceId,
                                   UsbDriverListener listener, long wiredHandle, long gipHandle) {
        super(deviceId, listener, device.getVendorId(), device.getProductId(), gipHandle);

        this.wiredHandle = wiredHandle;
        this.connection = connection;
        this.gipInterface = gipInterface;

        String reported = device.getSerialNumber();
        this.serial = reported != null ? reported : device.getDeviceName();
    }

    /** @return an identity that survives re-enumeration, unlike the device name */
    public String getSerial() {
        return serial;
    }

    /**
     * Claims the pad and brings the GIP stack up on it.
     *
     * @return the controller, or null if the device could not be claimed — in which case nothing
     *         has been left running and the caller still owns {@code connection}
     */
    public static XboxWiredGipController create(UsbDevice device, UsbDeviceConnection connection,
                                                int deviceId, UsbDriverListener listener) {

        /*
         * Claimed here rather than natively, and forced. Android's own driver holds this interface
         * - that is the whole reason the pad works without us - and the only thing that detaches it
         * is this call with force set. libusb_claim_interface on the wrapped descriptor would lose
         * to the kernel driver with EBUSY, so the native side deliberately does not attempt it.
         */
        UsbInterface gipInterface = device.getInterface(GIP_INTERFACE);

        if (!connection.claimInterface(gipInterface, true)) {
            LimeLog.warning("Wired GIP: could not claim the GIP interface from the kernel driver");
            return null;
        }

        long wired = createWiredDriver(connection.getFileDescriptor());
        if (wired == 0) {
            LimeLog.warning("Wired GIP: could not create the native transport");
            connection.releaseInterface(gipInterface);
            return null;
        }

        // Every failure past this point has to destroy the transport, or the claimed interface and
        // the read thread outlive the attempt.
        if (!startWiredDriver(wired)) {
            LimeLog.warning("Wired GIP: transport failed to start");
            destroyWiredDriver(wired);
            connection.releaseInterface(gipInterface);
            return null;
        }

        long gip = wiredControllerHandle(wired);
        if (gip == 0) {
            LimeLog.warning("Wired GIP: transport started without a controller");
            destroyWiredDriver(wired);
            connection.releaseInterface(gipInterface);
            return null;
        }

        return new XboxWiredGipController(device, connection, gipInterface, deviceId, listener,
                                          wired, gip);
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

        // Transport first: it is still reading from the interface until it is destroyed
        destroyWiredDriver(handle);
        connection.releaseInterface(gipInterface);
        connection.close();

        // Tells the service to drop us, which is also what takes this pad out of PadAudioSink
        // before the native controller behind it is gone.
        notifyDeviceRemoved();
    }

    /**
     * Tears the driver down and re-enumerates the pad, which is what pulling its cable does.
     *
     * <p>One method rather than a reset the caller sequences itself, because the ordering is the
     * whole difficulty. The native driver has to stop first, or its read thread and libusb event
     * thread are still working a descriptor that is about to be reset out from under them — but the
     * connection has to stay <em>open</em> across the reset, since the reset needs its descriptor.
     * {@link #stop()} closes it, so this cannot be built out of {@code stop()} plus a reset.
     *
     * <p>Afterwards the pad is gone from the bus and will attach again by itself.
     * {@code UsbDriverService} sees the detach and attach broadcasts and re-claims it.
     *
     * @return whether the device was reset. The controller is torn down and removed either way.
     */
    public boolean resetAndStop() {
        if (wiredHandle == 0) {
            return false;
        }

        long handle = wiredHandle;
        wiredHandle = 0;

        // Threads first, connection still open: the reset below needs the descriptor
        destroyWiredDriver(handle);
        connection.releaseInterface(gipInterface);

        boolean reset = resetWiredDevice(connection.getFileDescriptor());

        // Dead either way now - a successful reset re-enumerates the device out from under it
        connection.close();

        notifyDeviceRemoved();

        return reset;
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

    /**
     * Re-enumerates the pad, which is what pulling the cable does.
     *
     * <p>The one recovery for the audio state a killed process leaves behind — see
     * {@code jni/xow_driver/AUDIO.md} for the six GIP-level attempts that do not work. The pad is
     * battery powered, so a cable pull is not a power cycle but a <em>disconnect</em>, which
     * MS-GIPUSB 2.2.11 names as one of the three things that end an audio stream.
     *
     * <p>The connection is dead either way once this returns: a successful reset re-enumerates the
     * device out from under it. The caller must release everything and let the attach broadcast
     * bring the pad back.
     *
     * @return whether the device was reset
     */
    public static native boolean resetWiredDevice(int fd);
}
