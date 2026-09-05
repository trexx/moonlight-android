package com.limelight.binding.usbip.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes USB/IP messages.
 *
 * <p>Pure and free of Android imports and {@code LimeLog}, so it is reachable from a JVM test —
 * the {@code KeyMapper} and {@code StickCalibration} precedent. That matters more here than
 * elsewhere: usbip-win2 does not resynchronise a stream it cannot parse, it drops the attachment,
 * so a one-byte layout error presents as "the device disconnects immediately" with nothing in any
 * log to say why. The tests carry the wire capture from the kernel's own protocol document for
 * exactly this reason.
 *
 * <p>Every field is big-endian. Buffers handed in are set to {@link ByteOrder#BIG_ENDIAN} rather
 * than trusted, because a {@code ByteBuffer} defaults to big-endian but a sliced or wrapped one
 * inherits whatever it was given.
 *
 * <p>Payloads are deliberately not read here. A transfer buffer's length depends on fields inside
 * the header being decoded, and its bytes may not have arrived yet; the caller owns the socket and
 * reads them once this has told it how many to expect.
 */
public final class UsbIpCodec {

    private UsbIpCodec() {
    }

    // ---------------------------------------------------------------- op phase

    /**
     * Writes the eight-byte header every op-phase message starts with.
     */
    public static void encodeOpHeader(ByteBuffer out, short code, int status) {
        out.order(ByteOrder.BIG_ENDIAN);
        out.putShort(UsbIpProtocol.VERSION);
        out.putShort(code);
        out.putInt(status);
    }

    /**
     * Reads the opcode from an op-phase message, leaving the buffer positioned after the header.
     *
     * <p>The version the client sent is not checked. usbip-win2 announces v1.1.1 and so do we, but
     * refusing anything else would turn a client that is merely newer into a device that cannot be
     * attached, and the op-phase layout has not changed.
     */
    public static short decodeOpCode(ByteBuffer in) {
        in.order(ByteOrder.BIG_ENDIAN);
        in.getShort(); // version
        short code = in.getShort();
        in.getInt(); // status: unused in a request
        return code;
    }

    /**
     * Encodes an {@code OP_REP_DEVLIST} listing every exported device, with its interfaces.
     */
    public static byte[] encodeDevListReply(ExportedDevice[] devices) {
        int size = UsbIpProtocol.OP_HEADER_BYTES + Integer.BYTES;
        for (ExportedDevice device : devices) {
            size += UsbIpProtocol.EXPORTED_DEVICE_BYTES
                    + device.interfaces.length * UsbIpProtocol.INTERFACE_BYTES;
        }

        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        encodeOpHeader(out, UsbIpProtocol.OP_REP_DEVLIST, UsbIpProtocol.ST_OK);
        out.putInt(devices.length);
        for (ExportedDevice device : devices) {
            encodeExportedDevice(out, device, true);
        }
        return out.array();
    }

    /**
     * Reads the bus ID a client wants to import.
     *
     * @param in positioned at the start of the message, header included
     */
    public static String decodeImportRequest(ByteBuffer in) {
        decodeOpCode(in);
        return readFixedString(in, UsbIpProtocol.BUSID_BYTES);
    }

    /**
     * Encodes an {@code OP_REP_IMPORT}.
     *
     * <p>On success this carries the device struct <em>without</em> its interface array, unlike a
     * device list. On failure the message ends after the status field. Getting either wrong leaves
     * trailing bytes that the client reads as the start of the URB stream.
     *
     * @param device the device being imported, or {@code null} when {@code status} is not
     *               {@link UsbIpProtocol#ST_OK}
     */
    public static byte[] encodeImportReply(ExportedDevice device, int status) {
        boolean ok = status == UsbIpProtocol.ST_OK && device != null;
        int size = UsbIpProtocol.OP_HEADER_BYTES + (ok ? UsbIpProtocol.EXPORTED_DEVICE_BYTES : 0);

        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        encodeOpHeader(out, UsbIpProtocol.OP_REP_IMPORT, ok ? UsbIpProtocol.ST_OK : UsbIpProtocol.ST_NA);
        if (ok) {
            encodeExportedDevice(out, device, false);
        }
        return out.array();
    }

    /**
     * Writes one {@code usbip_usb_device}, optionally followed by its interface array.
     *
     * <p>{@code bNumInterfaces} is written from the array length whether or not the entries
     * themselves follow, because a client sizes the rest of the message from it either way.
     */
    static void encodeExportedDevice(ByteBuffer out, ExportedDevice device, boolean withInterfaces) {
        writeFixedString(out, device.path, UsbIpProtocol.PATH_BYTES);
        writeFixedString(out, device.busId, UsbIpProtocol.BUSID_BYTES);

        out.putInt(device.busNumber);
        out.putInt(device.deviceNumber);
        out.putInt(device.speed);

        out.putShort((short) device.vendorId);
        out.putShort((short) device.productId);
        out.putShort((short) device.deviceVersion);

        out.put((byte) device.deviceClass);
        out.put((byte) device.deviceSubClass);
        out.put((byte) device.deviceProtocol);
        out.put((byte) device.configurationValue);
        out.put((byte) device.configurationCount);
        out.put((byte) device.interfaces.length);

        if (withInterfaces) {
            for (ExportedDevice.Interface iface : device.interfaces) {
                out.put((byte) iface.interfaceClass);
                out.put((byte) iface.interfaceSubClass);
                out.put((byte) iface.interfaceProtocol);
                out.put((byte) 0); // Alignment byte, required to be zero
            }
        }
    }

    // --------------------------------------------------------------- URB phase

    /**
     * Reads the command word of a URB message without consuming it, so the caller can dispatch to
     * the matching decoder.
     */
    public static int peekUrbCommand(ByteBuffer in) {
        in.order(ByteOrder.BIG_ENDIAN);
        return in.getInt(in.position());
    }

    /**
     * Decodes a {@code USBIP_CMD_SUBMIT} header.
     *
     * @param in positioned at the command word, holding at least
     *           {@link UsbIpProtocol#URB_HEADER_BYTES} bytes
     * @return the request; its {@code outData} is left null for the caller to fill from the socket
     *         when the direction is OUT
     */
    public static SubmitUrb decodeSubmitUrb(ByteBuffer in) {
        in.order(ByteOrder.BIG_ENDIAN);
        int start = in.position();

        in.getInt(); // command, already dispatched on
        SubmitUrb urb = new SubmitUrb();
        urb.sequenceNumber = in.getInt();
        urb.deviceId = in.getInt();
        urb.direction = in.getInt();
        urb.endpoint = in.getInt();

        urb.transferFlags = in.getInt();
        urb.transferBufferLength = in.getInt();
        urb.startFrame = in.getInt();
        urb.numberOfPackets = in.getInt();
        urb.interval = in.getInt();
        in.get(urb.setup);

        in.position(start + UsbIpProtocol.URB_HEADER_BYTES);
        return urb;
    }

    /**
     * Encodes a {@code USBIP_RET_SUBMIT}, payload included when there is one.
     *
     * <p>{@code devid}, {@code direction} and {@code ep} are zeroed rather than echoed: the spec
     * requires it of a server, and mirroring the request instead is the natural mistake.
     */
    public static byte[] encodeSubmitReply(SubmitUrb.Reply reply) {
        byte[] data = reply.inData;
        int dataLength = data != null ? Math.min(data.length, Math.max(reply.actualLength, 0)) : 0;

        ByteBuffer out = ByteBuffer.allocate(UsbIpProtocol.URB_HEADER_BYTES + dataLength)
                .order(ByteOrder.BIG_ENDIAN);

        out.putInt(UsbIpProtocol.RET_SUBMIT);
        out.putInt(reply.sequenceNumber);
        out.putInt(0); // devid
        out.putInt(0); // direction
        out.putInt(0); // ep

        out.putInt(reply.status);
        out.putInt(reply.actualLength);
        out.putInt(reply.startFrame);
        out.putInt(reply.numberOfPackets);
        out.putInt(reply.errorCount);
        out.putLong(0L); // padding

        if (dataLength > 0) {
            out.put(data, 0, dataLength);
        }
        return out.array();
    }

    /**
     * Decodes a {@code USBIP_CMD_UNLINK} header.
     */
    public static UnlinkUrb decodeUnlinkUrb(ByteBuffer in) {
        in.order(ByteOrder.BIG_ENDIAN);
        int start = in.position();

        in.getInt(); // command, already dispatched on
        UnlinkUrb urb = new UnlinkUrb();
        urb.sequenceNumber = in.getInt();
        urb.deviceId = in.getInt();
        in.getInt(); // direction, zero for unlink
        in.getInt(); // ep, required to be zero for unlink
        urb.unlinkSequenceNumber = in.getInt();

        in.position(start + UsbIpProtocol.URB_HEADER_BYTES);
        return urb;
    }

    /**
     * Encodes a {@code USBIP_RET_UNLINK}.
     */
    public static byte[] encodeUnlinkReply(UnlinkUrb.Reply reply) {
        ByteBuffer out = ByteBuffer.allocate(UsbIpProtocol.URB_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN);

        out.putInt(UsbIpProtocol.RET_UNLINK);
        out.putInt(reply.sequenceNumber);
        out.putInt(0); // devid
        out.putInt(0); // direction
        out.putInt(0); // ep

        out.putInt(reply.status);
        // Remaining 24 bytes are padding, and allocate() already zeroed them.
        return out.array();
    }

    // ----------------------------------------------------------------- strings

    /**
     * Writes an ASCII string into a fixed-width, NUL-terminated, zero-padded field.
     *
     * <p>Truncates to leave room for the terminator. A client uses the bus ID it reads from a
     * device list as the key it sends back to import, so a silently untruncated string would make
     * the device unimportable rather than merely mislabelled.
     */
    static void writeFixedString(ByteBuffer out, String value, int fieldBytes) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int length = Math.min(bytes.length, fieldBytes - 1);

        int start = out.position();
        out.put(bytes, 0, length);
        for (int i = length; i < fieldBytes; i++) {
            out.put((byte) 0);
        }
        out.position(start + fieldBytes);
    }

    /**
     * Reads a fixed-width, NUL-terminated ASCII field, always consuming the whole field.
     */
    static String readFixedString(ByteBuffer in, int fieldBytes) {
        byte[] bytes = new byte[fieldBytes];
        in.get(bytes);

        int length = 0;
        while (length < fieldBytes && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }
}
