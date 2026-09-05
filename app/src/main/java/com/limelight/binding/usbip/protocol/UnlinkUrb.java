package com.limelight.binding.usbip.protocol;

/**
 * The {@code USBIP_CMD_UNLINK} exchange: a client cancelling a transfer it submitted earlier, and
 * the {@code USBIP_RET_UNLINK} that answers it.
 *
 * <p>This is not a rare path. A Windows host unlinks routinely — when a driver stops polling, when
 * a device is detached, when a pipe is reset — so a server that ignores unlinks leaks a blocked
 * thread per cancelled transfer and eventually stops answering.
 *
 * <p>Two sequence numbers are in play and confusing them is the classic error here.
 * {@link #sequenceNumber} identifies the unlink message itself and is what the reply echoes;
 * {@link #unlinkSequenceNumber} names the earlier {@link SubmitUrb} being cancelled. The reply
 * carries the former.
 */
public final class UnlinkUrb {

    /** Identifies this unlink request; the reply echoes this, not {@link #unlinkSequenceNumber}. */
    public int sequenceNumber;
    /** {@code (busnum << 16) | devnum} — see {@link UsbIpProtocol#deviceId(int, int)}. */
    public int deviceId;
    /** Sequence number of the {@link SubmitUrb} to cancel. */
    public int unlinkSequenceNumber;

    /**
     * The {@code USBIP_RET_UNLINK} answering an {@link UnlinkUrb}.
     *
     * <p>{@link #status} is {@code -ECONNRESET} when the transfer was still outstanding and was
     * cancelled, and {@code 0} when it had already completed and its {@code USBIP_RET_SUBMIT} has
     * gone out. Both are success; they tell the client whether to expect that reply.
     */
    public static final class Reply {

        /** Linux {@code ECONNRESET}, negated. The value is the wire contract, not this box's errno. */
        public static final int STATUS_CANCELLED = -104;
        public static final int STATUS_ALREADY_COMPLETED = 0;

        public int sequenceNumber;
        public int status;

        public Reply() {
        }

        public static Reply to(UnlinkUrb request, int status) {
            Reply reply = new Reply();
            reply.sequenceNumber = request.sequenceNumber;
            reply.status = status;
            return reply;
        }
    }
}
