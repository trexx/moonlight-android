package com.limelight.binding.usbip.protocol;

/**
 * Infers a device's USB bus speed from its descriptors.
 *
 * <p>This exists because Android's USB host API never reports speed. {@code UsbDevice} exposes
 * vendor, product, class and the interface and endpoint tree, but nothing that says whether the
 * device enumerated at low, full, high or super speed — and {@code usbip_usb_device.speed} is a
 * required field, sent before the client has asked the device anything.
 *
 * <p>Getting it wrong is not cosmetic. The Windows client builds its virtual port from this value
 * and then validates the descriptors it reads against it; a mismatch surfaces as "This device
 * cannot start. (Code 10)" in Device Manager with no further explanation. It is the first thing to
 * suspect when a device enumerates on Linux but not on Windows.
 *
 * <p>The inference is elimination rather than a lookup: each descriptor fact rules speeds out, and
 * what survives is reported. Where several survive the slowest is chosen, because every constraint
 * below is an upper bound that a slower bus also satisfies — claiming a speed the device cannot
 * sustain is the error that breaks enumeration, while under-claiming only affects how the virtual
 * host controller schedules, and the real transfers still happen at real speed on this box.
 *
 * <p>Pure by construction: no Android imports, no {@code LimeLog}, so it is reachable from a JVM
 * test. Endpoint type values are the USB specification's own {@code bmAttributes} transfer types,
 * which is also what {@code UsbConstants.USB_ENDPOINT_XFER_*} holds, so the caller can pass
 * Android's values through unchanged.
 */
public final class DeviceSpeedResolver {

    private DeviceSpeedResolver() {
    }

    // Endpoint transfer types, from bmAttributes bits 1:0 (USB 2.0 §9.6.6, Table 9-13).
    public static final int XFER_CONTROL = 0;
    public static final int XFER_ISOCHRONOUS = 1;
    public static final int XFER_BULK = 2;
    public static final int XFER_INTERRUPT = 3;

    // Values of enum usb_device_speed, from include/uapi/linux/usb/ch9.h. usbip_usb_device.speed
    // carries these verbatim.
    public static final int SPEED_UNKNOWN = 0;
    public static final int SPEED_LOW = 1;
    public static final int SPEED_FULL = 2;
    public static final int SPEED_HIGH = 3;
    public static final int SPEED_WIRELESS = 4;
    public static final int SPEED_SUPER = 5;
    public static final int SPEED_SUPER_PLUS = 6;

    private static final int FLAG_LOW = 1 << SPEED_LOW;
    private static final int FLAG_FULL = 1 << SPEED_FULL;
    private static final int FLAG_HIGH = 1 << SPEED_HIGH;
    private static final int FLAG_SUPER = 1 << SPEED_SUPER;

    /**
     * One endpoint's shape, which is all the inference needs from it.
     *
     * <p>Deliberately not a record: {@code minSdk} is 30 here and records rely on D8 desugaring
     * below API 33, which this project has never exercised. A protocol codec is the wrong place to
     * also be discovering toolchain behaviour.
     */
    public static final class Endpoint {

        final int transferType;
        final int maxPacketSize;

        public Endpoint(int transferType, int maxPacketSize) {
            this.transferType = transferType;
            this.maxPacketSize = maxPacketSize;
        }
    }

    /**
     * Resolves the bus speed of a device with the given control-endpoint packet size and endpoints.
     *
     * @param maxPacketSize0 {@code bDeviceDescriptor.bMaxPacketSize0}, in bytes. For SuperSpeed
     *                       devices the descriptor stores the base-2 exponent (9), so callers that
     *                       read the raw descriptor byte must expand it to 512 first.
     * @param endpoints      every endpoint on every interface of the active configuration
     * @return one of the {@code SPEED_*} values, or {@link #SPEED_UNKNOWN} if the descriptors
     *         contradict each other
     */
    public static int resolve(int maxPacketSize0, Endpoint[] endpoints) {
        int possible = FLAG_LOW | FLAG_FULL | FLAG_HIGH | FLAG_SUPER;

        // The control endpoint's packet size is the strongest single signal, because USB 2.0 §5.5.3
        // fixes the legal values per speed rather than giving a range.
        switch (maxPacketSize0) {
            case 8 -> possible &= ~(FLAG_HIGH | FLAG_SUPER); // High speed mandates exactly 64
            case 16, 32 -> possible &= ~(FLAG_LOW | FLAG_HIGH | FLAG_SUPER); // Full speed only
            case 64 -> possible &= ~(FLAG_LOW | FLAG_SUPER); // Low speed mandates 8, super 512
            case 512 -> possible &= ~(FLAG_LOW | FLAG_FULL | FLAG_HIGH);
            default -> {
                // Not a legal bMaxPacketSize0. Constrain nothing and let the endpoints decide,
                // rather than rejecting a device over a field we only use as a hint.
            }
        }

        for (Endpoint endpoint : endpoints) {
            switch (endpoint.transferType) {
                case XFER_BULK -> {
                    // Low speed has no bulk endpoints at all (USB 2.0 §5.8.1).
                    possible &= ~FLAG_LOW;
                    // Full speed caps bulk at 64 bytes; high speed mandates exactly 512.
                    if (endpoint.maxPacketSize > 64) {
                        possible &= ~FLAG_FULL;
                    }
                    if (endpoint.maxPacketSize < 512) {
                        possible &= ~FLAG_SUPER;
                    }
                }
                case XFER_ISOCHRONOUS -> {
                    // Low speed has no isochronous endpoints either (USB 2.0 §5.6.1).
                    possible &= ~FLAG_LOW;
                    if (endpoint.maxPacketSize > 1023) {
                        possible &= ~FLAG_FULL;
                    }
                }
                case XFER_INTERRUPT -> {
                    // Low speed interrupt endpoints carry at most 8 bytes, full speed at most 64.
                    if (endpoint.maxPacketSize > 8) {
                        possible &= ~FLAG_LOW;
                    }
                    if (endpoint.maxPacketSize > 64) {
                        possible &= ~FLAG_FULL;
                    }
                }
                default -> {
                    // Control endpoints beyond ep0 constrain nothing useful.
                }
            }
        }

        // Slowest surviving speed; see the class comment for why slowest rather than fastest.
        if ((possible & FLAG_LOW) != 0) {
            return SPEED_LOW;
        }
        if ((possible & FLAG_FULL) != 0) {
            return SPEED_FULL;
        }
        if ((possible & FLAG_HIGH) != 0) {
            return SPEED_HIGH;
        }
        if ((possible & FLAG_SUPER) != 0) {
            return SPEED_SUPER;
        }
        return SPEED_UNKNOWN;
    }
}
