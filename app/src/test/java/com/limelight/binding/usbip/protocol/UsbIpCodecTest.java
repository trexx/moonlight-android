package com.limelight.binding.usbip.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the USB/IP wire codec.
 *
 * <p>The interesting cases are pinned against the wire capture published in the kernel's own
 * {@code Documentation/usb/usbip_protocol.rst}, rather than by round-tripping this encoder through
 * this decoder. A round trip only proves the two halves agree with each other, and both halves
 * were written from the same reading of the same document — if that reading is wrong the round
 * trip passes anyway. Real bytes from a real HID device are the only check that catches it.
 *
 * <p>This matters more than usual because the failure mode is silent: usbip-win2 does not
 * resynchronise a stream it cannot parse, it drops the attachment, so a layout error reaches the
 * user as "the device disconnects immediately" with nothing logged anywhere to explain it.
 */
class UsbIpCodecTest {

    /** {@code CmdIntrIN} from the specification's capture: an interrupt IN submission. */
    private static final String CMD_INTR_IN =
            "00000001 00000d05 0001000f 00000001 00000001 00000200 00000040 ffffffff"
                    + " 00000000 00000004 00000000 00000000";

    /** {@code CmdIntrOUT}: the same, outbound, with a 64-byte payload. */
    private static final String CMD_INTR_OUT =
            "00000001 00000d06 0001000f 00000000 00000001 00000000 00000040 ffffffff"
                    + " 00000000 00000004 00000000 00000000";

    private static final String CMD_INTR_OUT_PAYLOAD =
            "ffffffff860008a784ce5ae21237630000000000000000000000000000000000"
                    + "0000000000000000000000000000000000000000000000000000000000000000";

    /** {@code RetIntrOut}: the reply to the outbound submission, header only. */
    private static final String RET_INTR_OUT =
            "00000003 00000d06 00000000 00000000 00000000 00000000 00000040 ffffffff"
                    + " 00000000 00000000 00000000 00000000";

    /** {@code RetIntrIn}: the reply to the inbound submission, carrying 64 bytes back. */
    private static final String RET_INTR_IN =
            "00000003 00000d05 00000000 00000000 00000000 00000000 00000040 ffffffff"
                    + " 00000000 00000000 00000000 00000000";

    private static final String RET_INTR_IN_PAYLOAD =
            "ffffffff860011a784ce5ae2123763612891b102010000040000000000000000"
                    + "0000000000000000000000000000000000000000000000000000000000000000";

    private static byte[] hex(String spaced) {
        String compact = spaced.replace(" ", "");
        byte[] bytes = new byte[compact.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] joined = new byte[head.length + tail.length];
        System.arraycopy(head, 0, joined, 0, head.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    @Nested
    @DisplayName("captured interrupt transfers")
    class CapturedTransfers {

        @Test
        @DisplayName("decodes the specification's interrupt IN submission")
        void decodesCapturedInboundSubmission() {
            byte[] wire = hex(CMD_INTR_IN);
            assertEquals(UsbIpProtocol.URB_HEADER_BYTES, wire.length,
                    "the capture should be exactly one URB header long");

            ByteBuffer in = ByteBuffer.wrap(wire);
            assertEquals(UsbIpProtocol.CMD_SUBMIT, UsbIpCodec.peekUrbCommand(in));

            SubmitUrb urb = UsbIpCodec.decodeSubmitUrb(in);
            assertEquals(0x0d05, urb.sequenceNumber);
            assertEquals(UsbIpProtocol.deviceId(1, 15), urb.deviceId);
            assertEquals(UsbIpProtocol.DIR_IN, urb.direction);
            assertEquals(1, urb.endpoint);
            assertEquals(0x200, urb.transferFlags);
            assertEquals(64, urb.transferBufferLength);
            assertEquals(4, urb.interval);
            assertEquals(UsbIpProtocol.URB_HEADER_BYTES, in.position(),
                    "the whole padded header must be consumed, not just the fields read");
        }

        @Test
        @DisplayName("reads start_frame as -1 and number_of_packets as 0 on a non-ISO transfer")
        void readsTheFramingFieldsAsCaptured() {
            // The prose says number_of_packets is 0xffffffff when a transfer is not isochronous.
            // The capture in the same document says otherwise, and real clients follow the
            // capture. Pinning both fields here is what stops isIsochronous() being "simplified"
            // back to a comparison against NUMBER_OF_PACKETS_NON_ISO.
            SubmitUrb urb = UsbIpCodec.decodeSubmitUrb(ByteBuffer.wrap(hex(CMD_INTR_IN)));

            assertEquals(-1, urb.startFrame);
            assertEquals(0, urb.numberOfPackets);
            assertFalse(UsbIpProtocol.isIsochronous(urb.numberOfPackets),
                    "a zero packet count means not isochronous, not an empty ISO transfer");
        }

        @Test
        @DisplayName("decodes the outbound submission and sizes its payload")
        void decodesCapturedOutboundSubmission() {
            SubmitUrb urb = UsbIpCodec.decodeSubmitUrb(ByteBuffer.wrap(hex(CMD_INTR_OUT)));

            assertEquals(0x0d06, urb.sequenceNumber);
            assertEquals(UsbIpProtocol.DIR_OUT, urb.direction);
            assertEquals(64, urb.transferBufferLength);
            assertEquals(64, hex(CMD_INTR_OUT_PAYLOAD).length,
                    "an OUT transfer's payload is transfer_buffer_length bytes");
        }

        @Test
        @DisplayName("reproduces the captured reply to an outbound transfer, byte for byte")
        void encodesCapturedOutboundReply() {
            SubmitUrb request = UsbIpCodec.decodeSubmitUrb(ByteBuffer.wrap(hex(CMD_INTR_OUT)));

            SubmitUrb.Reply reply = SubmitUrb.Reply.to(request);
            reply.status = 0;
            reply.actualLength = 64;

            assertArrayEquals(hex(RET_INTR_OUT), UsbIpCodec.encodeSubmitReply(reply));
        }

        @Test
        @DisplayName("reproduces the captured reply to an inbound transfer, payload included")
        void encodesCapturedInboundReply() {
            SubmitUrb request = UsbIpCodec.decodeSubmitUrb(ByteBuffer.wrap(hex(CMD_INTR_IN)));

            SubmitUrb.Reply reply = SubmitUrb.Reply.to(request);
            reply.status = 0;
            reply.actualLength = 64;
            reply.inData = hex(RET_INTR_IN_PAYLOAD);

            assertArrayEquals(concat(hex(RET_INTR_IN), hex(RET_INTR_IN_PAYLOAD)),
                    UsbIpCodec.encodeSubmitReply(reply));
        }

        @Test
        @DisplayName("zeroes devid, direction and ep in a reply rather than echoing them")
        void zeroesServerOnlyFieldsInReplies() {
            // The request names a device and an endpoint; the reply must not. Mirroring the
            // request is the natural mistake and the capture shows zeros in all three.
            SubmitUrb request = UsbIpCodec.decodeSubmitUrb(ByteBuffer.wrap(hex(CMD_INTR_IN)));
            SubmitUrb.Reply reply = SubmitUrb.Reply.to(request);
            reply.actualLength = 0;

            ByteBuffer encoded = ByteBuffer.wrap(UsbIpCodec.encodeSubmitReply(reply));
            assertEquals(UsbIpProtocol.RET_SUBMIT, encoded.getInt());
            assertEquals(request.sequenceNumber, encoded.getInt());
            assertEquals(0, encoded.getInt(), "devid");
            assertEquals(0, encoded.getInt(), "direction");
            assertEquals(0, encoded.getInt(), "ep");
        }
    }

    @Nested
    @DisplayName("isochronous detection")
    class IsochronousDetection {

        @Test
        @DisplayName("treats both zero and 0xffffffff as not isochronous")
        void acceptsBothNonIsoEncodings() {
            assertFalse(UsbIpProtocol.isIsochronous(0),
                    "what real clients send for a non-ISO transfer");
            assertFalse(UsbIpProtocol.isIsochronous(UsbIpProtocol.NUMBER_OF_PACKETS_NON_ISO),
                    "what the specification's prose asks for");
            assertTrue(UsbIpProtocol.isIsochronous(1));
            assertTrue(UsbIpProtocol.isIsochronous(8));
        }
    }

    @Nested
    @DisplayName("unlink")
    class Unlink {

        @Test
        @DisplayName("echoes the unlink's own sequence number, not the one being cancelled")
        void echoesTheUnlinkSequenceNumber() {
            // Two sequence numbers are in play and swapping them is the classic error: the client
            // would wait forever for a reply to an unlink it never sees answered.
            ByteBuffer out = ByteBuffer.allocate(UsbIpProtocol.URB_HEADER_BYTES);
            out.putInt(UsbIpProtocol.CMD_UNLINK);
            out.putInt(0x1111); // this unlink
            out.putInt(UsbIpProtocol.deviceId(1, 15));
            out.putInt(0); // direction
            out.putInt(0); // ep
            out.putInt(0x2222); // the submission being cancelled

            UnlinkUrb urb = UsbIpCodec.decodeUnlinkUrb(ByteBuffer.wrap(out.array()));
            assertEquals(0x1111, urb.sequenceNumber);
            assertEquals(0x2222, urb.unlinkSequenceNumber);

            byte[] reply = UsbIpCodec.encodeUnlinkReply(
                    UnlinkUrb.Reply.to(urb, UnlinkUrb.Reply.STATUS_CANCELLED));

            ByteBuffer in = ByteBuffer.wrap(reply);
            assertEquals(UsbIpProtocol.RET_UNLINK, in.getInt());
            assertEquals(0x1111, in.getInt(), "the reply echoes the unlink, not its target");
        }

        @Test
        @DisplayName("pads an unlink reply to the common URB header length")
        void padsReplyToHeaderLength() {
            UnlinkUrb urb = new UnlinkUrb();
            byte[] reply = UsbIpCodec.encodeUnlinkReply(UnlinkUrb.Reply.to(urb, 0));

            assertEquals(UsbIpProtocol.URB_HEADER_BYTES, reply.length,
                    "all four URB messages share one header length; a short one desyncs the stream");
        }
    }

    @Nested
    @DisplayName("device list and import")
    class DeviceListAndImport {

        private static ExportedDevice device(String busId, int interfaceCount) {
            ExportedDevice device = new ExportedDevice();
            device.path = "/sys/devices/platform/usb/" + busId;
            device.busId = busId;
            device.busNumber = 1;
            device.deviceNumber = 15;
            device.speed = DeviceSpeedResolver.SPEED_FULL;
            device.vendorId = 0x045e;
            device.productId = 0x02ea;
            device.deviceVersion = 0x0100;
            device.configurationValue = 1;
            device.configurationCount = 1;

            device.interfaces = new ExportedDevice.Interface[interfaceCount];
            for (int i = 0; i < interfaceCount; i++) {
                device.interfaces[i] = new ExportedDevice.Interface(0xff, 0x47, 0xd0);
            }
            return device;
        }

        @Test
        @DisplayName("sizes a device list from the struct and interface widths")
        void sizesDeviceList() {
            byte[] reply = UsbIpCodec.encodeDevListReply(
                    new ExportedDevice[]{device("1-1", 2), device("1-2", 1)});

            int expected = UsbIpProtocol.OP_HEADER_BYTES + Integer.BYTES
                    + 2 * UsbIpProtocol.EXPORTED_DEVICE_BYTES
                    + 3 * UsbIpProtocol.INTERFACE_BYTES;
            assertEquals(expected, reply.length);

            ByteBuffer in = ByteBuffer.wrap(reply);
            assertEquals(UsbIpProtocol.OP_REP_DEVLIST, UsbIpCodec.decodeOpCode(in));
            assertEquals(2, in.getInt(), "device count");
        }

        @Test
        @DisplayName("omits the interface array from an import reply")
        void importReplyCarriesNoInterfaces() {
            // A device list and an import reply carry the same struct but disagree about what
            // follows it. Emitting interfaces here leaves bytes the client reads as the first URB.
            byte[] reply = UsbIpCodec.encodeImportReply(device("1-1", 3), UsbIpProtocol.ST_OK);

            assertEquals(UsbIpProtocol.OP_HEADER_BYTES + UsbIpProtocol.EXPORTED_DEVICE_BYTES,
                    reply.length);
        }

        @Test
        @DisplayName("ends a failed import reply after the status field")
        void failedImportReplyIsHeaderOnly() {
            byte[] reply = UsbIpCodec.encodeImportReply(null, UsbIpProtocol.ST_NA);

            assertEquals(UsbIpProtocol.OP_HEADER_BYTES, reply.length);
            assertEquals(UsbIpProtocol.OP_REP_IMPORT,
                    UsbIpCodec.decodeOpCode(ByteBuffer.wrap(reply)));
        }

        @Test
        @DisplayName("still reports a device struct of exactly 312 bytes")
        void deviceStructWidthIsFixed() {
            // Guards the field list itself: adding or widening a field silently shifts everything
            // after it, and no other assertion in this file would notice.
            byte[] reply = UsbIpCodec.encodeImportReply(device("1-1", 0), UsbIpProtocol.ST_OK);
            assertEquals(312, reply.length - UsbIpProtocol.OP_HEADER_BYTES);
        }

        @Test
        @DisplayName("round-trips a bus ID through an import request")
        void roundTripsImportBusId() {
            ByteBuffer out = ByteBuffer.allocate(
                    UsbIpProtocol.OP_HEADER_BYTES + UsbIpProtocol.BUSID_BYTES);
            UsbIpCodec.encodeOpHeader(out, UsbIpProtocol.OP_REQ_IMPORT, 0);
            UsbIpCodec.writeFixedString(out, "1-4.2", UsbIpProtocol.BUSID_BYTES);

            assertEquals("1-4.2", UsbIpCodec.decodeImportRequest(ByteBuffer.wrap(out.array())));
        }
    }

    @Nested
    @DisplayName("fixed-width strings")
    class FixedWidthStrings {

        @Test
        @DisplayName("zero-pads a short value and consumes the whole field")
        void padsShortValues() {
            ByteBuffer out = ByteBuffer.allocate(32);
            UsbIpCodec.writeFixedString(out, "1-1", 32);

            assertEquals(32, out.position());
            assertEquals("1-1", UsbIpCodec.readFixedString(ByteBuffer.wrap(out.array()), 32));
        }

        @Test
        @DisplayName("truncates a long value leaving room for the terminator")
        void truncatesLongValues() {
            // A bus ID is the key a client sends back to import the device, so an untruncated
            // string does not merely mislabel it - it makes the device unimportable.
            String tooLong = "0123456789012345678901234567890123456789";
            ByteBuffer out = ByteBuffer.allocate(32);
            UsbIpCodec.writeFixedString(out, tooLong, 32);

            String read = UsbIpCodec.readFixedString(ByteBuffer.wrap(out.array()), 32);
            assertEquals(31, read.length(), "31 characters plus a NUL fills the field");
            assertEquals(tooLong.substring(0, 31), read);
            assertEquals(0, out.array()[31], "the field must stay NUL-terminated");
        }

        @Test
        @DisplayName("does not read past a terminator into stale bytes")
        void stopsAtTheTerminator() {
            byte[] field = new byte[32];
            field[0] = '1';
            field[1] = '-';
            field[2] = '1';
            field[3] = 0;
            field[4] = 'X'; // Left over from a previous, longer value

            assertEquals("1-1", UsbIpCodec.readFixedString(ByteBuffer.wrap(field), 32));
        }
    }
}
