package com.limelight.binding.usbip.protocol;

/**
 * The {@code USBIP_CMD_SUBMIT} exchange: a client asking this box to run one USB transfer, and the
 * {@code USBIP_RET_SUBMIT} that answers it.
 *
 * <p>Every transfer the host performs arrives as one of these, so the fields here are the entire
 * vocabulary between the Windows host's virtual host controller and a real device on this box.
 *
 * <p>The two directions share {@code usbip_header_basic} and are both padded to
 * {@link UsbIpProtocol#URB_HEADER_BYTES}, but they are not symmetric: a request carries its
 * payload when the direction is OUT, a reply when it is IN, and the reply zeroes
 * {@code devid}, {@code direction} and {@code ep} rather than echoing them.
 */
public final class SubmitUrb {

    /** Identifies this request; the reply must carry the same value. */
    public int sequenceNumber;
    /** {@code (busnum << 16) | devnum} — see {@link UsbIpProtocol#deviceId(int, int)}. */
    public int deviceId;
    /** {@link UsbIpProtocol#DIR_IN} or {@link UsbIpProtocol#DIR_OUT}. */
    public int direction;
    /** Endpoint number, without the direction bit. Zero is the control endpoint. */
    public int endpoint;

    public int transferFlags;
    public int transferBufferLength;
    public int startFrame;
    /** Interpret with {@link UsbIpProtocol#isIsochronous(int)}, never by direct comparison. */
    public int numberOfPackets;
    public int interval;

    /** The eight raw setup bytes, meaningful only on the control endpoint. */
    public byte[] setup = new byte[UsbIpProtocol.SETUP_BYTES];

    /** Payload for an OUT transfer; {@code null} for IN, where the device supplies the data. */
    public byte[] outData;

    /**
     * The {@code USBIP_RET_SUBMIT} answering a {@link SubmitUrb}.
     *
     * <p>{@link #status} is a negative errno on failure, not a USB/IP status code — the client
     * hands it to the Windows URB layer more or less directly.
     */
    public static final class Reply {

        public int sequenceNumber;
        public int status;
        public int actualLength;
        public int startFrame;
        public int numberOfPackets;
        public int errorCount;

        /** Payload for an IN transfer; {@code null} for OUT. */
        public byte[] inData;

        public Reply() {
        }

        /**
         * Starts a reply to {@code request}, carrying its sequence number and echoing the frame
         * and packet-count fields.
         *
         * <p>Those two are echoed rather than zeroed because clients disagree with the spec about
         * their non-isochronous values — the capture in the kernel's own protocol document shows
         * {@code start_frame = 0xffffffff} and {@code number_of_packets = 0} in both directions of
         * an interrupt transfer. Returning what the client sent cannot be the thing that surprises
         * it. See {@link UsbIpProtocol#NUMBER_OF_PACKETS_NON_ISO}.
         */
        public static Reply to(SubmitUrb request) {
            Reply reply = new Reply();
            reply.sequenceNumber = request.sequenceNumber;
            reply.startFrame = request.startFrame;
            reply.numberOfPackets = request.numberOfPackets;
            return reply;
        }
    }
}
