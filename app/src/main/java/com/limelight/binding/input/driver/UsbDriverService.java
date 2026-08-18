package com.limelight.binding.input.driver;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.view.InputDevice;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import com.limelight.binding.audio.PadAudioSink;

import java.util.ArrayList;
import java.util.List;

/**
 * Bound service that owns every USB controller this app drives itself.
 *
 * <p>It watches for USB attachments, decides which devices are worth claiming from Android's
 * input stack ({@link #shouldClaimDevice}), requests permission, constructs the matching
 * {@link AbstractController} and forwards its events to the bound client — normally {@code Game},
 * which passes them to {@code ControllerHandler}.
 *
 * <p>It exists as a service rather than living in the activity because claiming a USB device is
 * process-wide state: the permission grant, the claimed interfaces and the reader threads all
 * have to survive configuration changes and outlive any single activity instance.
 *
 * <p>Note that claiming is deliberately conservative. Taking a device Android already supports
 * would replace a working driver with ours for no gain, so most claims are conditional on the
 * kernel not handling the device — or on the user forcing it with the "bind all USB" preference.
 */
public class UsbDriverService extends Service implements UsbDriverListener {

    // Private action for the USB permission dialog result, delivered to our own receiver
    private static final String ACTION_USB_PERMISSION =
            "com.limelight.USB_PERMISSION";

    private UsbManager usbManager;
    private PreferenceConfiguration prefConfig;
    private boolean started;

    private final UsbEventReceiver receiver = new UsbEventReceiver();
    private final UsbDriverBinder binder = new UsbDriverBinder();

    private final ArrayList<AbstractController> controllers = new ArrayList<>();

    // Client callback, null when nothing is bound
    private UsbDriverListener listener;
    // Monotonic ID handed to each new controller; never reused within a process
    private int nextDeviceId;

    // Dongles are tracked separately: one dongle produces several controllers of its own, so it
    // isn't an AbstractController itself but still needs stopping when the service shuts down
    private final ArrayList<XboxWirelessDongle> xboxWirelessDongles = new ArrayList<>();

    @Override
    public void reportControllerState(int controllerId, int buttonFlags, float leftStickX, float leftStickY,
                                      float rightStickX, float rightStickY, float leftTrigger, float rightTrigger) {
        // Call through to the client's listener
        if (listener != null) {
            listener.reportControllerState(controllerId, buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ) {
        // Call through to the client's listener
        if (listener != null) {
            listener.reportControllerMotion(controllerId, motionType, motionX, motionY, motionZ);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void reportControllerBattery(int controllerId, byte batteryState, byte batteryPercentage) {
        // Call through to the client's listener
        if (listener != null) {
            listener.reportControllerBattery(controllerId, batteryState, batteryPercentage);
        }
    }

    /** {@inheritDoc} Also drops the controller from our own list before forwarding. */
    @Override
    public void deviceRemoved(AbstractController controller) {
        // Remove the the controller from our list (if not removed already)
        controllers.remove(controller);

        // A pad taking stream audio must leave the sink before its native driver instance is
        // destroyed, or the audio thread keeps queueing into a freed handle. This is the only
        // place a removal is known - the audio renderer is not on the listener chain - so the
        // coupling to the audio package is deliberate and lives here rather than being invented
        // somewhere with less claim to know.
        if (padAudioSink != null && controller instanceof GipController wireless) {
            padAudioSink.disable(wireless);
        }

        // Call through to the client's listener
        if (listener != null) {
            listener.deviceRemoved(controller);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deviceAdded(AbstractController controller) {
        // Call through to the client's listener
        if (listener != null) {
            listener.deviceAdded(controller);
        }
    }

    /** Receives USB attach broadcasts and the result of our permission dialog. */
    public class UsbEventReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // Initial attachment broadcast
            if (action.equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                logUsbInterfaces(device);

                // shouldClaimDevice() looks at the kernel's enumerated input
                // devices to make its decision about whether to prompt to take
                // control of the device. The kernel bringing up the input stack
                // may race with this callback and cause us to prompt when the
                // kernel is capable of running the device. Let's post a delayed
                // message to process this state change to allow the kernel
                // some time to bring up the stack.
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Continue the state machine
                        handleUsbDeviceState(device);
                    }
                }, 1000);
            }
            // Subsequent permission dialog completion intent
            else if (action.equals(ACTION_USB_PERMISSION)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                // If we got this far, we've already found we're able to handle this device
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    handleUsbDeviceState(device);
                }
            }
        }
    }

    /** Control surface handed to bound clients. */
    public class UsbDriverBinder extends Binder {
        /**
         * Installs the input event sink. Controllers claimed before binding are replayed to the
         * new listener immediately, so a client that binds late still learns about them.
         */
        public void setListener(UsbDriverListener listener) {
            UsbDriverService.this.listener = listener;

            // Report all controllerMap that already exist
            if (listener != null) {
                for (AbstractController controller : controllers) {
                    listener.deviceAdded(controller);
                }
            }
        }

        /** Begins watching for devices and claims any that are already attached. */
        public void start() {
            UsbDriverService.this.start();
        }

        /** Stops watching and releases every claimed device. */
        public void stop() {
            UsbDriverService.this.stop();
        }

        /**
         * @return true if an Xbox wireless adapter is claimed and running. Stricter than "one is
         *         plugged in": a dongle only lands in the list once its driver has started, so
         *         this answers whether {@link #setDonglePairingMode} can do anything.
         */
        public boolean hasXboxWirelessDongle() {
            return !xboxWirelessDongles.isEmpty();
        }

        /** Turns pairing mode on or off for every claimed adapter; see the service method. */
        public void setDonglePairingMode(boolean enable) {
            UsbDriverService.this.setDonglePairingMode(enable);
        }

        /**
         * @return every GIP controller we are driving, whatever it is attached by: pads paired
         *         through an adapter in pairing order, then any on a cable. This is what the
         *         in-game menu lists so the user can pick which pads get audio.
         *
         * <p>Cabled pads belong here as much as wireless ones — a {@link XboxWiredGipController}
         * is a {@link GipController} with an isochronous transport under it, and its headphone
         * jack works the same way. Listing only the adapter's pads is what made the audio menu
         * entry invisible with a cable plugged in and no adapter pad paired.
         */
        public List<GipController> getGipControllers() {
            List<GipController> found = new ArrayList<>();

            for (XboxWirelessDongle dongle : xboxWirelessDongles) {
                found.addAll(dongle.getControllers());
            }

            for (AbstractController controller : controllers) {
                if (controller instanceof GipController gip) {
                    found.add(gip);
                }
            }

            return found;
        }

        /**
         * Hands the service the sink so a disconnecting pad can be taken out of it before its
         * native instance goes away. Without this the sink would keep a dangling handle.
         */
        public void setPadAudioSink(PadAudioSink sink) {
            padAudioSink = sink;
        }
    }

    // Set by the client once it binds; see UsbDriverBinder.setPadAudioSink()
    private PadAudioSink padAudioSink;

    /**
     * Puts every claimed Xbox wireless adapter into (or out of) pairing mode, reporting the
     * outcome to the user.
     *
     * <p>This exists because the adapter's physical pairing button is the only trigger the
     * bundled driver has, and that button is dead on some units — leaving no way to pair at all.
     * It sets the same state Windows does from "Add a device".
     *
     * <p>The work is a handful of USB transfers, so it runs off the main thread. The list is
     * copied first because it is main-thread-owned.
     */
    private void setDonglePairingMode(boolean enable) {
        final ArrayList<XboxWirelessDongle> dongles = new ArrayList<>(xboxWirelessDongles);

        new Thread(() -> {
            boolean succeeded = !dongles.isEmpty();
            for (XboxWirelessDongle dongle : dongles) {
                if (!dongle.setPairingMode(enable)) {
                    succeeded = false;
                }
            }

            final int message = succeeded
                    ? R.string.toast_dongle_pairing_enabled
                    : R.string.toast_dongle_pairing_failed;
            new Handler(Looper.getMainLooper()).post(
                    () -> Toast.makeText(UsbDriverService.this, message, Toast.LENGTH_LONG).show());
        }).start();
    }

    /**
     * Advances a device through claim, permission and construction. Re-entered after the
     * permission dialog completes, hence the repeated eligibility check.
     */
    /**
     * Logs a device's interfaces and endpoints, in debug builds only.
     *
     * <p>Written to answer one question about controller audio: MS-GIPUSB 2.2.12 puts a cabled
     * pad's audio on interface #1 with isochronous endpoints — 228 bytes out, 64 in, at 1 ms — and
     * whether a given pad actually has them decides whether audio over a cable is worth pursuing.
     * Android cannot perform isochronous transfers, so this can only look; driving those endpoints
     * would mean libusb, the way the wireless adapter is already driven.
     */
    private static void logUsbInterfaces(UsbDevice device) {
        if (!BuildConfig.DEBUG) {
            return;
        }

        LimeLog.info("USB device " + String.format("%04x:%04x", device.getVendorId(), device.getProductId()) +
                " has " + device.getInterfaceCount() + " interface(s)");

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            StringBuilder line = new StringBuilder();

            line.append("  interface ").append(iface.getId())
                    .append(" alt ").append(iface.getAlternateSetting())
                    .append(String.format(" class %02x sub %02x proto %02x",
                            iface.getInterfaceClass(), iface.getInterfaceSubclass(),
                            iface.getInterfaceProtocol()));

            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint endpt = iface.getEndpoint(e);

                line.append(String.format(" | ep %02x %s max %d interval %d",
                        endpt.getAddress(),
                        endpt.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC ? "ISOC"
                                : endpt.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK ? "bulk"
                                : String.valueOf(endpt.getType()),
                        endpt.getMaxPacketSize(), endpt.getInterval()));
            }

            LimeLog.info(line.toString());
        }
    }

    private void handleUsbDeviceState(UsbDevice device) {
        // Are we able to operate it?
        if (shouldClaimDevice(device, prefConfig.bindAllUsb)) {
            // Do we have permission yet?
            if (!usbManager.hasPermission(device)) {
                // Let's ask for permission
                try {
                    int intentFlags = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // This PendingIntent must be mutable to allow the framework to populate EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED.
                        intentFlags |= PendingIntent.FLAG_MUTABLE;
                    }

                    // This function is not documented as throwing any exceptions (denying access
                    // is indicated by calling the PendingIntent with a false result). However,
                    // Samsung Knox has some policies which block this request, but rather than
                    // just returning a false result or returning 0 enumerated devices,
                    // they throw an undocumented SecurityException from this call, crashing
                    // the whole app. :(

                    // Use an explicit intent to activate our unexported broadcast receiver, as required on Android 14+
                    Intent i = new Intent(ACTION_USB_PERMISSION);
                    i.setPackage(getPackageName());

                    usbManager.requestPermission(device, PendingIntent.getBroadcast(UsbDriverService.this, 0, i, intentFlags));
                } catch (SecurityException e) {
                    Toast.makeText(this, this.getText(R.string.error_usb_prohibited), Toast.LENGTH_LONG).show();
                }
                return;
            }

            // Open the device
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                LimeLog.warning("Unable to open USB device: "+device.getDeviceName());
                return;
            }

            if (XboxWirelessDongle.canClaimDevice(device)) {
                var dongle = new XboxWirelessDongle(device, connection, this);
                if(!dongle.start()) {
                    connection.close();
                    return;
                }
                xboxWirelessDongles.add(dongle);
                return;
            }

            AbstractController controller;

            /*
             * Which driver goes on a cabled Xbox pad, not whether to take it - that is already
             * settled above, by the same "override native Xbox gamepad support" preference that
             * has always decided it. Both drivers match the same hardware and both replace
             * Android's; they differ in what they implement.
             *
             * XboxOneController sends canned init packets and parses two message types, with no
             * metadata, no device-state machine and no security handshake. MS-GIPUSB 2.2.1.4 makes
             * that handshake the gate on the audio sub-device, so headphone audio is unreachable
             * from it however much isochronous plumbing were added beneath. The GIP driver is a
             * superset and should eventually replace it outright - but it is unproven, so it is
             * opt-in until it has been run against real pads.
             */
            if (prefConfig.wiredPadAudio && XboxWiredGipController.canClaimDevice(device)) {
                controller = XboxWiredGipController.create(device, connection, nextDeviceId++, this);

                // create() releases the interface but leaves the connection open on failure, which
                // is exactly what the fallback needs - so fall back rather than giving up, and the
                // user loses audio rather than their controller.
                if (controller != null) {
                    controllers.add(controller);
                    controller.start();
                    return;
                }

                LimeLog.warning("Falling back to the standard driver for this cabled pad");
            }

            if (XboxOneController.canClaimDevice(device)) {
                controller = new XboxOneController(device, connection, nextDeviceId++, this);
            }
            else if (Xbox360Controller.canClaimDevice(device)) {
                controller = new Xbox360Controller(device, connection, nextDeviceId++, this);
            }
            else if (Xbox360WirelessDongle.canClaimDevice(device)) {
                controller = new Xbox360WirelessDongle(device, connection, nextDeviceId++, this);
            }
            else if (ProConController.canClaimDevice(device)) {
                controller = new ProConController(device, connection, nextDeviceId++, this);
            }
            else {
                // Unreachable
                return;
            }

            // Start the controller
            if (!controller.start()) {
                connection.close();
                return;
            }

            // Add this controller to the list
            controllers.add(controller);
        }
    }

    /**
     * @return true if Android has already enumerated an input device with this VID/PID, meaning
     *         a kernel driver is handling it and we should leave it alone
     */
    public static boolean isRecognizedInputDevice(UsbDevice device) {
        // Determine if this VID and PID combo matches an existing input device
        // and defer to the built-in controller support in that case.
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice inputDev = InputDevice.getDevice(id);
            if (inputDev == null) {
                // Device was removed while looping
                continue;
            }

            if (inputDev.getVendorId() == device.getVendorId() &&
                    inputDev.getProductId() == device.getProductId()) {
                return true;
            }
        }

        return false;
    }

    /**
     * @return true if this kernel's xpad driver is expected to handle Xbox One pads correctly.
     *         Inferred from the kernel version because there is no way to interrogate the driver
     *         directly from an app sandbox.
     */
    public static boolean kernelSupportsXboxOne() {
        String kernelVersion = System.getProperty("os.version");
        LimeLog.info("Kernel Version: "+kernelVersion);

        if (kernelVersion == null) {
            // We'll assume this is some newer version of Android
            // that doesn't let you read the kernel version this way.
            return true;
        }
        else if (kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.")) {
            // These are old kernels that definitely don't support Xbox One controllers properly
            return false;
        }
        else if (kernelVersion.startsWith("4.4.") || kernelVersion.startsWith("4.9.")) {
            // These aren't guaranteed to have backported kernel patches for proper Xbox One
            // support (though some devices will).
            return false;
        }
        else {
            // The next AOSP common kernel is 4.14 which has working Xbox One controller support
            return true;
        }
    }

    /**
     * @return true if this kernel's xpad driver is expected to set the Xbox 360 wireless
     *         controller's player LEDs. Without that, the pad's ring keeps flashing and the
     *         player number is never assigned, so we claim the dongle ourselves instead.
     */
    public static boolean kernelSupportsXbox360W() {
        // Check if this kernel is 4.2+ to see if the xpad driver sets Xbox 360 wireless LEDs
        // https://github.com/torvalds/linux/commit/75b7f05d2798ee3a1cc5bbdd54acd0e318a80396
        String kernelVersion = System.getProperty("os.version");
        if (kernelVersion != null) {
            if (kernelVersion.startsWith("2.") || kernelVersion.startsWith("3.") ||
                    kernelVersion.startsWith("4.0.") || kernelVersion.startsWith("4.1.")) {
                // Even if LED devices are present, the driver won't set the initial LED state.
                return false;
            }
        }

        // We know we have a kernel that should set Xbox 360 wireless LEDs, but we still don't
        // know if CONFIG_JOYSTICK_XPAD_LEDS was enabled during the kernel build. Unfortunately
        // it's not possible to detect this reliably due to Android's app sandboxing. Reading
        // /proc/config.gz and enumerating /sys/class/leds are both blocked by SELinux on any
        // relatively modern device. We will assume that CONFIG_JOYSTICK_XPAD_LEDS=y on these
        // kernels and users can override by using the settings option to claim all devices.
        return true;
    }

    /**
     * Decides whether to take a device over from Android's input stack. Each supported family
     * has its own reason for being claimed, and every one of them can be forced by the user.
     *
     * @param claimAllAvailable the "bind all USB devices" preference, which overrides the
     *                          kernel-support heuristics for devices we can drive at all
     * @return true if this device should be claimed
     */
    public static boolean shouldClaimDevice(UsbDevice device, boolean claimAllAvailable) {
        return ((!kernelSupportsXboxOne() || !isRecognizedInputDevice(device) || claimAllAvailable) && XboxOneController.canClaimDevice(device)) ||
                ((!isRecognizedInputDevice(device) || claimAllAvailable) && Xbox360Controller.canClaimDevice(device)) ||
                // We must not call isRecognizedInputDevice() because wireless controllers don't share the same product ID as the dongle
                ((!kernelSupportsXbox360W() || claimAllAvailable) && Xbox360WirelessDongle.canClaimDevice(device) ||
                        XboxWirelessDongle.canClaimDevice(device)) ||
                // Android's hid-nintendo driver handles this controller's buttons and sticks, so
                // we only take it over when the user has asked us to. Doing so gains motion sensors.
                ((!isRecognizedInputDevice(device) || claimAllAvailable) && ProConController.canClaimDevice(device));
    }

    /** Registers for attach broadcasts, then sweeps the devices that are already plugged in. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void start() {
        if (started || usbManager == null) {
            return;
        }

        started = true;

        // Register for USB attach broadcasts and permission completions
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        }
        else {
            registerReceiver(receiver, filter);
        }

        // Enumerate existing devices
        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            // Before the claim check, so a device we decline is still described
            logUsbInterfaces(dev);

            if (shouldClaimDevice(dev, prefConfig.bindAllUsb)) {
                // Start the process of claiming this device
                handleUsbDeviceState(dev);
            }
        }
    }

    /**
     * Releases everything claimed. Each {@code stop()} call removes the controller from
     * {@link #controllers} via {@link #deviceRemoved}, so the lists are drained from the front
     * rather than iterated.
     */
    private void stop() {
        if (!started) {
            return;
        }

        started = false;

        // Stop the attachment receiver
        unregisterReceiver(receiver);

        // Stop all dongles
        while (xboxWirelessDongles.size() > 0) {
            // Stop and remove the dongle
            xboxWirelessDongles.remove(0).stop();
        }

        // Stop all controllers
        while (controllers.size() > 0) {
            // Stop and remove the controller
            controllers.remove(0).stop();
        }
    }

    /** {@inheritDoc} Resolves the USB manager and reads preferences; nothing is claimed until start(). */
    @Override
    public void onCreate() {
        this.usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        this.prefConfig = PreferenceConfiguration.readPreferences(this);
    }

    /** {@inheritDoc} Releases every claimed device and drops the listeners. */
    @Override
    public void onDestroy() {
        stop();

        // Remove the listener
        listener = null;
    }

    /** {@inheritDoc} @return the binder clients use to start, stop and listen */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
