package com.limelight.binding.input.driver;

import android.content.Context;
import android.content.SharedPreferences;
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
    }

    /**
     * Claims the pad and brings the GIP stack up on it.
     *
     * @return the controller, or null if the device could not be claimed — in which case nothing
     *         has been left running and the caller still owns {@code connection}
     */
    /**
     * Records whether audio is streaming to a cabled pad, so the next run can tell whether this one
     * ended cleanly.
     *
     * <p>A pad left configured by a run that died mid-stream plays stretched and gapped until it is
     * physically unplugged — nothing over GIP shifts it. This is how {@link #create} knows to
     * re-enumerate it rather than doing so on every connect, which put the device into an attach
     * loop with a permission prompt each time round.
     *
     * <p>Written synchronously: the point of it is to survive a process that is about to be killed
     * without warning, and a queued write would not.
     */
    /**
     * Re-enumerates the pad if the previous run died while audio was streaming.
     *
     * <p>Separate from {@link #create} because the outcomes differ: failing to create means fall
     * back to the standard driver, while a reset means claim nothing at all yet and wait for the
     * device to come back. Conflating them made the caller fall through to
     * {@link XboxOneController} holding a connection this had already closed.
     *
     * @return true if the device was reset, in which case the connection is closed, nothing should
     *         be claimed now, and Android will send a fresh attach when it re-enumerates
     */
    public static boolean resetIfPreviousSessionUnclean(Context context,
                                                        UsbDeviceConnection connection) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (!prefs.getBoolean(PREF_AUDIO_ACTIVE, false)) {
            return false;
        }

        /*
         * A pad left configured by a run that died mid-stream plays stretched and gapped however it
         * is set up afterwards, and nothing sent over GIP shifts it. Re-enumerating is what
         * unplugging the cable does, and that is the only recovery ever found by hand. xone resets
         * on every probe; this resets only when it is needed, because on Android it costs a
         * reconnect where on Linux it costs nothing.
         *
         * Cleared first, so a reset that fixes nothing cannot make every future connect reset too.
         */
        prefs.edit().putBoolean(PREF_AUDIO_ACTIVE, false).commit();

        LimeLog.warning("Wired GIP: previous session left audio running, re-enumerating the pad");

        resetWiredDevice(connection.getFileDescriptor());

        // Dead either way now: a successful reset re-enumerates the device out from under it
        connection.close();

        return true;
    }

    public static void setAudioActive(Context context, boolean active) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUDIO_ACTIVE, active)
                .commit();
    }

    private static final String PREFS = "wired_gip";
    private static final String PREF_AUDIO_ACTIVE = "audio_active";

    public static XboxWiredGipController create(Context context, UsbDevice device,
                                                UsbDeviceConnection connection,
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
     * @return whether this device is a cabled GIP pad. The same descriptor test
     *         {@link XboxOneController} uses, because it is the same hardware — what differs is
     *         which driver we put on it.
     */
    public static boolean canClaimDevice(UsbDevice device) {
        return XboxOneController.canClaimDevice(device);
    }

    private static native boolean resetWiredDevice(int fd);
    private static native long createWiredDriver(int fd);
    private static native boolean startWiredDriver(long handle);
    private static native long wiredControllerHandle(long handle);
    private static native void destroyWiredDriver(long handle);
}
