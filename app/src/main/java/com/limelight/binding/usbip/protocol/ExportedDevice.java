package com.limelight.binding.usbip.protocol;

/**
 * One device this box offers to a USB/IP client — the {@code usbip_usb_device} struct, plus the
 * interface descriptors that follow it in a device list.
 *
 * <p>A plain field holder rather than a record, for the reason given on
 * {@link DeviceSpeedResolver.Endpoint}: {@code minSdk} is 30 and record desugaring below API 33 is
 * unexercised here.
 *
 * <p>{@link #path} and {@link #busId} are the Linux sysfs strings a client expects. Android has no
 * sysfs path to offer, so the server synthesises both; the only hard requirement is that the
 * {@link #busId} a client reads from a device list is the one it can send back in an import
 * request, and that it survives a round trip through 32 bytes of fixed-width ASCII.
 */
public final class ExportedDevice {

    /**
     * One entry of the interface array trailing a device in {@code OP_REP_DEVLIST}.
     *
     * <p>Absent from {@code OP_REP_IMPORT}, which carries the device struct alone — a difference
     * that is easy to miss and desynchronises the stream if got wrong.
     */
    public static final class Interface {

        public int interfaceClass;
        public int interfaceSubClass;
        public int interfaceProtocol;

        public Interface() {
        }

        public Interface(int interfaceClass, int interfaceSubClass, int interfaceProtocol) {
            this.interfaceClass = interfaceClass;
            this.interfaceSubClass = interfaceSubClass;
            this.interfaceProtocol = interfaceProtocol;
        }
    }

    /** Synthetic sysfs-style path, e.g. {@code /sys/devices/platform/usb/1-1}. */
    public String path = "";
    /** Bus identifier a client names this device by, e.g. {@code 1-1}. */
    public String busId = "";

    public int busNumber;
    public int deviceNumber;
    /** One of {@code DeviceSpeedResolver.SPEED_*}. */
    public int speed;

    public int vendorId;
    public int productId;
    public int deviceVersion;

    public int deviceClass;
    public int deviceSubClass;
    public int deviceProtocol;
    public int configurationValue;
    public int configurationCount;

    /**
     * Interfaces of the active configuration.
     *
     * <p>{@code bNumInterfaces} on the wire is written from this array's length rather than stored
     * separately, so the count and the entries cannot disagree.
     */
    public Interface[] interfaces = new Interface[0];
}
