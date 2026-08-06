package com.limelight.nvstream.av.video;

/**
 * Sink for encoded video, driven by moonlight-common-c through {@code MoonBridge}.
 *
 * <p>The implementation registered with {@code MoonBridge.setupBridge()} owns video decoding and
 * presentation for the whole session. In this app that is
 * {@code com.limelight.binding.video.MediaCodecDecoderRenderer}.
 *
 * <h2>Lifecycle</h2>
 * {@link #setup} once the stream format is negotiated, then {@link #start}, then
 * {@link #submitDecodeUnit} for every decode unit, then {@link #stop} and {@link #cleanup}.
 * {@link #getCapabilities()} may be called <em>before</em> {@link #setup}, since what it reports
 * feeds into the stream configuration being negotiated.
 *
 * <p>Callbacks arrive on native threads, and {@link #submitDecodeUnit} in particular runs on the
 * decode thread at the stream's full frame rate.
 */
public abstract class VideoDecoderRenderer {
    /**
     * Prepares to decode the negotiated format.
     *
     * @param format     {@code MoonBridge.VIDEO_FORMAT_*} bitmask identifying the codec and bit depth
     * @param redrawRate the stream's frame rate in Hz
     * @return 0 on success; any other value aborts the connection
     */
    public abstract int setup(int format, int width, int height, int redrawRate);

    /** Called once decoding should begin, after {@link #setup} succeeded. */
    public abstract void start();

    /** Called when the stream is ending, before {@link #cleanup}. */
    public abstract void stop();

    /**
     * Consumes one decode unit.
     *
     * @param decodeUnitData         buffer holding the unit, valid only for this call
     * @param decodeUnitType         {@code MoonBridge.BUFFER_TYPE_*}, distinguishing parameter
     *                               sets from frame data
     * @param frameNumber            host's frame counter, whose gaps indicate lost frames
     * @param frameType              {@code MoonBridge.FRAME_TYPE_*}; IDR frames reset decoder state
     * @param frameHostProcessingLatency host-side latency in tenths of a millisecond, or 0 if
     *                               the host doesn't report it
     * @return {@code MoonBridge.DR_OK}, or {@code DR_NEED_IDR} to have the host send a fresh IDR
     *         frame because the decoder can no longer use what came before
     */
    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeUs, long enqueueTimeUs);

    /** Releases all decoder resources. Nothing else is called afterwards. */
    public abstract void cleanup();

    /**
     * @return {@code MoonBridge.CAPABILITY_*} bitmask describing what this decoder supports, such
     *         as reference frame invalidation and slices per frame. Queried before {@link #setup},
     *         so it must not depend on the chosen codec.
     */
    public abstract int getCapabilities();

    /**
     * Notifies the decoder that the host has switched HDR on or off.
     *
     * @param hdrMetadata CTA-861.3 mastering display metadata, or null when HDR is off
     */
    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
