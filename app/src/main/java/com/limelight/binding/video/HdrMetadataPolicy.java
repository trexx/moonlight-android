package com.limelight.binding.video;

import java.util.Arrays;

/**
 * Decides whether an HDR announcement from the host needs the codec restarted.
 *
 * <p>The host's mastering-display metadata arrives on the control stream after the first frames
 * have already been decoded, and {@code KEY_HDR_STATIC_INFO} can only be applied at
 * {@code configure()}. Applying it therefore meant a full codec restart 57 ms into every HDR
 * stream, costing 4-5 frames (HARDWARE_TESTING.md section 28). But HEVC and AV1 carry the same
 * metadata in-band, and a decoder that parses it publishes {@code hdr-static-info} in its output
 * format and attaches it to its output buffers, which is what the display pipeline drives the
 * HDMI InfoFrame from. On such a decoder the key is redundant and so is the restart.
 *
 * <p>So the decision is made from what the decoder itself publishes. Until the first output
 * format has been seen the answer is deferred; once a format has shown static info that the
 * codec was <em>not</em> configured with, the decoder is known to parse the bitstream and no HDR
 * change ever restarts it. A format that shows nothing, or only echoes a key this app set, is
 * no evidence, and such a decoder gets exactly the old behaviour: a restart with the key on
 * every metadata change.
 *
 * <p>Known limit: a stream that starts SDR and switches to HDR later reports its first format
 * without static info, so this restarts once with the key even on a decoder that could have
 * parsed the SEI. HDR from the first frame is the case section 28 measured.
 *
 * <p>Pure and synchronised. Called from the control callback thread and the renderer thread, a
 * handful of times per stream and never per frame; the caller logs and acts on the answer.
 */
final class HdrMetadataPolicy {

    enum Action { NONE, RESTART, DEFER }

    // Latest effective metadata from the host; null while HDR is off
    private byte[] announced;
    private boolean everAnnounced;
    // Whether the decoder has been seen to publish static info it was not given. Sticky.
    private boolean publishesStaticInfo;
    private boolean formatSeen;
    // An announcement is waiting on the first output format
    private boolean pending;

    /**
     * @param enabled  the host's HDR state
     * @param metadata CTA-861.3 mastering display metadata, or null when HDR is off
     * @return what the codec needs: nothing, a restart with {@link #metadataToApply()} applied, or
     *         a decision deferred to {@link #onOutputFormat}
     */
    synchronized Action onHdrMode(boolean enabled, byte[] metadata) {
        byte[] effective = (enabled && metadata != null) ? metadata : null;

        // HDR is re-announced routinely; only a change means anything
        if (Arrays.equals(effective, announced)) {
            return Action.NONE;
        }
        announced = effective;
        if (effective != null) {
            everAnnounced = true;
        }

        if (publishesStaticInfo) {
            return Action.NONE;
        }
        if (!formatSeen) {
            pending = true;
            return Action.DEFER;
        }
        return Action.RESTART;
    }

    /**
     * @param hasStaticInfo            the output format carries {@code hdr-static-info}
     * @param configuredWithStaticInfo the codec was configured with {@code KEY_HDR_STATIC_INFO},
     *                                 in which case the format only echoes it and proves nothing
     *                                 about the bitstream
     * @return a restart if an announcement was waiting and the decoder turned out not to publish
     */
    synchronized Action onOutputFormat(boolean hasStaticInfo, boolean configuredWithStaticInfo) {
        formatSeen = true;
        if (hasStaticInfo && !configuredWithStaticInfo) {
            publishesStaticInfo = true;
        }

        if (!pending) {
            return Action.NONE;
        }
        pending = false;
        return publishesStaticInfo ? Action.NONE : Action.RESTART;
    }

    /**
     * @return the metadata to configure the codec with: null when the decoder follows the
     *         bitstream itself or HDR is off, otherwise the host's latest announcement
     */
    synchronized byte[] metadataToApply() {
        return publishesStaticInfo ? null : announced;
    }

    /** @return the branch this stream took, for the end-of-stream summary */
    synchronized String describe() {
        if (!everAnnounced) {
            return "never announced";
        }
        if (publishesStaticInfo) {
            return "in-band";
        }
        return formatSeen ? "via key" : "pending";
    }
}
