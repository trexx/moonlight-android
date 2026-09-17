package com.limelight.binding.video;

import java.util.ArrayList;
import java.util.List;

/**
 * The ladder of decoder low-latency options, one rung per {@code configure()} attempt.
 *
 * <p>There is no way to ask whether a decoder accepts a vendor option other than to configure it
 * and see, so {@code MediaCodecDecoderRenderer} loops: rung 0, configure, and on failure the next
 * rung, until a configuration is accepted or the ladder is exhausted. Rungs are ordered most to
 * least aggressive, so the first one accepted is the lowest-latency setup the decoder will take.
 *
 * <p>Split out of {@code MediaCodecHelper}, which cannot load on a JVM, so the ladder itself can
 * be tested. The keys are string literals matching {@code MediaFormat}'s constants rather than
 * references to them for the same reason.
 *
 * <p>Why the feature-advertising ladder differs from the other: a decoder that advertises
 * {@code FEATURE_LowLatency} used to get the official key alone on rung 0, and since that
 * configuration always succeeds, its vendor key and {@code KEY_PRIORITY} were never applied. The
 * Amlogic Codec2 decoders advertise the feature, so {@code vendor.low-latency.enable} - the key
 * that lowers Amlogic H.264 latency - was never reaching them. Rung 0 now carries all three; rung
 * 1 is the old rung 0, so a decoder that rejects the extras lands exactly where it did before.
 */
final class LowLatencyOptions {

    /** One integer {@code MediaFormat} key to set before {@code configure()}. */
    record Option(String key, int value) {}

    /** Vendor families with low-latency keys of their own; OTHER gets only the generic ones. */
    enum Family { QUALCOMM, KIRIN, EXYNOS, AMLOGIC, OTHER }

    // MediaFormat.KEY_LOW_LATENCY: the official Android 11+ option.
    static final String KEY_LOW_LATENCY = "low-latency";
    // MediaTek decoders don't use vendor-defined keys for low latency mode. Instead, they have a
    // modified version of AOSP's ACodec.cpp which supports the "vdec-lowlatency" option, passed
    // down to the decoder as OMX.MTK.index.param.video.LowLatencyDecode. It is also plumbed for
    // Amazon Amlogic-based devices like the Fire TV 3, where it not only reduces latency but fixes
    // the HEVC bug that stops the decoder outputting frames - and it does the exact opposite on
    // the Xiaomi MITV4-ANSM0, which is why it is a rung of its own rather than always on.
    // https://github.com/yuan1617/Framwork/blob/master/frameworks/av/media/libstagefright/ACodec.cpp
    static final String KEY_VDEC_LOW_LATENCY = "vdec-lowlatency";
    // MediaFormat.KEY_PRIORITY: 0 is realtime.
    static final String KEY_PRIORITY = "priority";
    // MediaFormat.KEY_OPERATING_RATE. On Qualcomm it is set to the maximum, alone: on those
    // platforms it lowers latency measurably (still significant on a Pixel 2), but a decoder
    // that cannot meet a ludicrous rate crashes - reliably on the Snapdragon 765G (Mi 10 Lite,
    // Redmi K30i) - so no other family gets that value. Every other family gets the actual
    // stream rate alongside KEY_PRIORITY, which is a different request: not "run as fast as you
    // can" but "this is the rate to size your clocks for", so the codec can pick a DVFS point up
    // front instead of ramping into the first seconds of the stream. ACodec and Codec2 both
    // treat a rate the decoder cannot honour as a logged failure to apply, not a configure
    // error, so it does not cost a rung.
    static final String KEY_OPERATING_RATE = "operating-rate";
    static final int MAX_OPERATING_RATE = Short.MAX_VALUE;

    // Vendor extensions, "vendor.<extension>.<parameter>". MediaCodec has passed these through
    // since Android 8.0. NOTE: MediaCodecHelper.knownVendorLowLatencyOptions must list every key
    // added here, since that is what the vendor-parameter probe matches against.
    //
    // Qualcomm, Snapdragon 845 and later:
    // https://cs.android.com/android/platform/superproject/+/master:hardware/qcom/sdm845/media/mm-video-v4l2/vidc/vdec/src/omx_vdec_extensions.hpp
    static final String KEY_QTI_PICTURE_ORDER = "vendor.qti-ext-dec-picture-order.enable";
    static final String KEY_QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable";
    // HiSilicon Kirin: https://developer.huawei.com/consumer/cn/forum/topic/0202325564295980115
    static final String KEY_HISI_LOW_LATENCY_REQ = "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req";
    static final String KEY_HISI_LOW_LATENCY_RDY = "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy";
    // Exynos H.264 decoder
    static final String KEY_EXYNOS_LOW_LATENCY = "vendor.rtc-ext-dec-low-latency.enable";
    // Amlogic: https://github.com/codewalkerster/android_vendor_amlogic_common_prebuilt_libstagefrighthw/commit/41fefc4e035c476d58491324a5fe7666bfc2989e
    static final String KEY_AMLOGIC_LOW_LATENCY = "vendor.low-latency.enable";

    private LowLatencyOptions() {}

    /**
     * @param tryNumber         configure attempt, from 0
     * @param family            the decoder's vendor family
     * @param featureLowLatency whether the decoder advertises {@code FEATURE_LowLatency}
     * @param fps               the stream frame rate, for {@code KEY_OPERATING_RATE}
     * @return the options to set for this attempt. Empty means the ladder is exhausted: configure
     *         with no options, and treat a failure then as a real failure.
     */
    static List<Option> forTry(int tryNumber, Family family, boolean featureLowLatency, int fps) {
        List<Option> options = new ArrayList<>();
        int rung = tryNumber;

        if (featureLowLatency) {
            if (rung == 0) {
                // The official key plus the vendor key and priority. See the class comment.
                options.add(new Option(KEY_LOW_LATENCY, 1));
                addPriority(options, family, fps);
                addVendorKeys(options, family, 0);
                return options;
            }
            if (rung == 1) {
                // The official key alone: a decoder that advertises the feature accepts this.
                options.add(new Option(KEY_LOW_LATENCY, 1));
                return options;
            }
            // Past that, the same ladder as a decoder without the feature, from its rung 1 on -
            // which is what a feature-advertising decoder always fell through to before.
            rung--;
        }

        if (rung < 1) {
            options.add(new Option(KEY_LOW_LATENCY, 1));
        }
        if (rung < 2) {
            options.add(new Option(KEY_VDEC_LOW_LATENCY, 1));
        }
        if (rung < 3) {
            addPriority(options, family, fps);
        }
        addVendorKeys(options, family, rung);
        return options;
    }

    private static void addPriority(List<Option> options, Family family, int fps) {
        if (family == Family.QUALCOMM) {
            options.add(new Option(KEY_OPERATING_RATE, MAX_OPERATING_RATE));
        } else {
            options.add(new Option(KEY_PRIORITY, 0));
            options.add(new Option(KEY_OPERATING_RATE, fps));
        }
    }

    private static void addVendorKeys(List<Option> options, Family family, int rung) {
        switch (family) {
            case QUALCOMM -> {
                // Both first, then the low-latency key alone if that fails
                if (rung < 4) {
                    options.add(new Option(KEY_QTI_PICTURE_ORDER, 1));
                }
                if (rung < 5) {
                    options.add(new Option(KEY_QTI_LOW_LATENCY, 1));
                }
            }
            case KIRIN -> {
                if (rung < 4) {
                    options.add(new Option(KEY_HISI_LOW_LATENCY_REQ, 1));
                    options.add(new Option(KEY_HISI_LOW_LATENCY_RDY, -1));
                }
            }
            case EXYNOS -> {
                if (rung < 4) {
                    options.add(new Option(KEY_EXYNOS_LOW_LATENCY, 1));
                }
            }
            case AMLOGIC -> {
                if (rung < 4) {
                    options.add(new Option(KEY_AMLOGIC_LOW_LATENCY, 1));
                }
            }
            case OTHER -> { }
        }
    }
}
