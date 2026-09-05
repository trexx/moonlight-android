package com.limelight.binding.usbip.protocol;

/**
 * Wire constants for the USB/IP protocol, version 1.1.1.
 *
 * <p>Field layouts follow the kernel's own specification,
 * {@code Documentation/usb/usbip_protocol.rst}. Everything on the wire is big-endian, which is why
 * the codec never relies on a platform default.
 *
 * <p>The <em>client</em> is the machine importing a device — here the Windows host running
 * usbip-win2 — and this box is the <em>server</em>, which exports them. That is the reverse of
 * what a streaming client intuitively expects, and it is the spec's naming, so it is kept
 * throughout rather than translated at the boundary.
 */
public final class UsbIpProtocol {

    private UsbIpProtocol() {
    }

    /** Protocol version this implementation speaks. usbip-win2 requires exactly v1.1.1. */
    public static final short VERSION = 0x0111;

    // Connection-setup opcodes, exchanged before any URB flows. The top bit distinguishes a
    // request from its reply: 0x8000 set means client-to-server.
    public static final short OP_REQ_DEVLIST = (short) 0x8005;
    public static final short OP_REP_DEVLIST = 0x0005;
    public static final short OP_REQ_IMPORT = (short) 0x8003;
    public static final short OP_REP_IMPORT = 0x0003;

    /** Status carried by an op-phase reply. */
    public static final int ST_OK = 0x00;
    public static final int ST_NA = 0x01;

    // URB-phase commands. Unlike the opcodes above these are 32-bit and not bit-flagged.
    public static final int CMD_SUBMIT = 0x00000001;
    public static final int CMD_UNLINK = 0x00000002;
    public static final int RET_SUBMIT = 0x00000003;
    public static final int RET_UNLINK = 0x00000004;

    public static final int DIR_OUT = 0;
    public static final int DIR_IN = 1;

    // Fixed sizes. These are wire facts, not implementation choices: one byte out of place
    // desynchronises the stream, and usbip-win2 drops the whole attachment rather than resyncing.
    /** version + code + status. */
    public static final int OP_HEADER_BYTES = 8;
    public static final int PATH_BYTES = 256;
    public static final int BUSID_BYTES = 32;
    /** One {@code usbip_usb_device}: path, busid, the numeric fields and six trailing bytes. */
    public static final int EXPORTED_DEVICE_BYTES = 312;
    /** One {@code usbip_usb_interface}: class, subclass, protocol and an alignment byte. */
    public static final int INTERFACE_BYTES = 4;
    /** {@code usbip_header_basic}: command, seqnum, devid, direction, ep. */
    public static final int URB_HEADER_BASIC_BYTES = 20;
    /** A full URB header. All four URB messages are padded to this length so they are uniform. */
    public static final int URB_HEADER_BYTES = 48;
    public static final int ISO_DESCRIPTOR_BYTES = 16;
    /** Setup packet in CMD_SUBMIT, zero-filled when the transfer is not a control transfer. */
    public static final int SETUP_BYTES = 8;

    /**
     * The value the spec tells clients to write in {@code number_of_packets} for a
     * non-isochronous transfer.
     *
     * <p>Do not compare against this directly — use {@link #isIsochronous(int)}. Real clients
     * disagree with the spec's prose here, and the specification contradicts itself: the wire
     * capture it ships shows an interrupt IN transfer carrying {@code start_frame = 0xffffffff}
     * and {@code number_of_packets = 0}, because Linux packs the URB's own fields straight through
     * and a non-isochronous URB has no packets. Accepting only {@code 0xffffffff} would read that
     * zero as an isochronous transfer with no packets and stall the endpoint.
     */
    public static final int NUMBER_OF_PACKETS_NON_ISO = 0xFFFFFFFF;

    /**
     * Whether a URB carrying this {@code number_of_packets} is isochronous.
     *
     * <p>Both {@code 0} and {@code 0xffffffff} mean "not isochronous"; see
     * {@link #NUMBER_OF_PACKETS_NON_ISO} for why both have to be accepted.
     */
    public static boolean isIsochronous(int numberOfPackets) {
        return numberOfPackets != 0 && numberOfPackets != NUMBER_OF_PACKETS_NON_ISO;
    }

    /**
     * Packs a bus and device number into the {@code devid} by which a client names a device.
     *
     * <p>A server echoes zero rather than this value in its replies. The spec requires that, and
     * symmetry makes it easy to get wrong.
     */
    public static int deviceId(int busNumber, int deviceNumber) {
        return (busNumber << 16) | (deviceNumber & 0xFFFF);
    }
}
