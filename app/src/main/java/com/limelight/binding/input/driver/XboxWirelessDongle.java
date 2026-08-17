package com.limelight.binding.input.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import com.limelight.LimeLog;
import com.limelight.binding.input.driver.UsbDriverListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Xbox One wireless adapter, driven through the bundled native {@code xow-driver}.
 *
 * <p>Unlike every other class here this is not an {@link AbstractController}: one adapter hosts
 * up to four pads, so it acts as a factory instead. The native driver owns the USB traffic and
 * the wireless protocol, and calls back into {@link #addNewController} and
 * {@link #removeController} as controllers pair and drop, each of which becomes an
 * {@link XboxWirelessController}.
 *
 * <p>Android has no driver for this adapter at all, so unlike the other families there is no
 * kernel support to weigh up — claiming it is the only way these controllers work.
 */
public class XboxWirelessDongle {
    private UsbDriverListener listener;
    protected final UsbDevice device;
    protected final UsbDeviceConnection connection;

    // Native driver instance, or -1 when not running
    private long driverHandle;

    // Live controllers by the native driver's slot index
    private Map<Integer, AbstractController> controllers = new HashMap<>();

    static {
        System.loadLibrary("xow-driver");
    }

    /** @param connection an already-open connection, whose file descriptor is handed to the native driver */
    public XboxWirelessDongle(UsbDevice device, UsbDeviceConnection connection, UsbDriverListener listener) {
        this.device = device;
        this.connection = connection;
        this.listener = listener;
        this.driverHandle = -1;
    }

    /**
     * Hands the open USB file descriptor to the native driver and starts it. Controllers appear
     * asynchronously afterwards via {@link #addNewController}, so a successful return only means
     * the adapter is running, not that anything is paired.
     *
     * @return true if the driver started
     *
     * <p>Synchronized against {@link #stop} and {@link #setPairingMode}: the pairing call runs
     * off the main thread, and the native handle must not be destroyed underneath it.
     */
    public synchronized boolean start() {
        if(this.driverHandle != -1) {
            return false; //we already started;
        }
        this.driverHandle = createDriver(connection.getFileDescriptor());
        boolean ok = startDriver(this.driverHandle, "");
        if(!ok) {
            LimeLog.info("xbox wireless dongle driver failed to start");
            destroyDriver(this.driverHandle);
            this.driverHandle = -1;
            return false;
        }
        return true;
    }

    /**
     * Stops and destroys the native driver, then reports every attached controller as removed.
     *
     * <p>Idempotent: the handle is cleared before anything else can fail, so a second call after
     * the driver has already been destroyed does nothing rather than reaching into freed memory.
     *
     * <p>Synchronized so it waits for an in-flight {@link #setPairingMode} rather than freeing
     * the handle from under it. That call is a handful of USB transfers — about a millisecond.
     */
    public synchronized void stop() {
        if(this.driverHandle == -1) {
            return; //we already cleaned;
        }

        // Clear the handle first so this can't be entered twice for the same driver instance
        long handle = this.driverHandle;
        this.driverHandle = -1;

        stopDriver(handle);
        destroyDriver(handle);

        // Drain into a local list before notifying: the listener chain runs arbitrary code, and
        // removing from the map while iterating it would throw before the last controller was
        // reported.
        var removedControllers = new ArrayList<>(controllers.values());
        controllers.clear();
        for (AbstractController controller : removedControllers) {
            this.listener.deviceRemoved(controller);
        }
    }

    /**
     * Turns the adapter's pairing mode on or off — the same state Windows sets from
     * "Add a device", and the only way in on an adapter whose physical pairing button is dead.
     *
     * <p>Blocks on a few USB transfers, so call it off the main thread. The driver clears
     * pairing mode by itself once a controller pairs.
     *
     * @return true if the adapter accepted the change
     */
    public synchronized boolean setPairingMode(boolean enable) {
        if (this.driverHandle == -1) {
            return false;
        }

        return setPairingModeNative(this.driverHandle, enable);
    }

    /** @return true if this is a Microsoft Xbox wireless adapter (either hardware revision) */
    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != 0x045e) {
            return false;
        }
        if (device.getProductId() != 0x02e6 &&  // Older one
                device.getProductId() != 0x02fe // new one
        ) {
            return false;
        }

        return true;
    }

    /**
     * Called from the native driver when a controller pairs.
     *
     * @param id     the driver's slot index
     * @param handle native controller instance, used for rumble
     * @param vid    the controller's own vendor ID, which differs from the adapter's
     * @param pid    the controller's own product ID
     */
    public void addNewController(int id, long handle, short vid, short pid){
        // Namespaced by vendor ID so slot indices can't collide with other drivers' device IDs
        var controller = new XboxWirelessController(id + 0x045e0000, listener, vid, pid, handle);
        controllers.put(id, controller);
        this.listener.deviceAdded(controller);
    }

    /**
     * @return the controllers currently paired to this adapter, ordered by slot so the first is
     *         the first that paired. Used by the in-game audio menu, which needs stable names.
     */
    public List<XboxWirelessController> getControllers() {
        var found = new ArrayList<XboxWirelessController>();
        for (var entry : new TreeMap<>(controllers).entrySet()) {
            if (entry.getValue() instanceof XboxWirelessController wireless) {
                found.add(wireless);
            }
        }
        return found;
    }

    /** Called from the native driver when a controller drops off. Unknown slots are ignored. */
    public void removeController(int id) {
        var controller = controllers.get(id);
        if(controller == null) {
            return;
        }
        controllers.remove(id);
        this.listener.deviceRemoved(controller);
    }

    private native long createDriver(int fd);
    private native boolean startDriver(long handle, String fwPath);
    private native boolean setPairingModeNative(long handle, boolean enable);
    private native void stopDriver(long handle);
    private native void destroyDriver(long handle);
}
