package com.limelight.binding.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.utils.TrafficStatsHelper;
import com.limelight.preferences.PreferenceConfiguration;

import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.CodecException;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Range;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Choreographer;
import android.view.SurfaceHolder;

/**
 * Hardware video decoder and presenter, built on {@link MediaCodec}.
 *
 * <p>This is where stream latency is won or lost, so most of what looks like complexity here is
 * latency work or device workarounds:
 *
 * <ul>
 *   <li><b>Decoder selection.</b> Not every decoder that advertises a codec can actually keep up,
 *       and some are outright broken. {@link MediaCodecHelper} holds the blacklists and quirk
 *       tables; the {@code find*Decoder()} methods below combine those with the platform's own
 *       performance points to pick a codec.</li>
 *   <li><b>Frame pacing.</b> Four modes, chosen by preference. In
 *       {@code FRAME_PACING_BALANCED} a dedicated Choreographer thread presents at most one frame
 *       per vsync via {@link #doFrame}; every other mode presents from the renderer thread as
 *       soon as frames come out, dropping all but the newest to stay current.</li>
 *   <li><b>Scheduler hints.</b> An ADPF hint session ({@link #createPerformanceHintSession()})
 *       tells the platform the per-frame deadline so CPU clocks are held where they need to be
 *       rather than ramped reactively after frames have already been missed.</li>
 *   <li><b>Codec recovery.</b> Decoders die mid-stream, particularly on cheap TV boxes. Rather
 *       than dropping the connection, the three threads that touch the codec are quiesced, the
 *       codec is flushed, restarted or reset, and streaming resumes — see
 *       {@link #doCodecRecoveryIfRequired(int)}.</li>
 * </ul>
 *
 * <h2>Threads</h2>
 * Four, and the {@code CR_FLAG_*} constants exist because three of them touch the codec:
 * <ol>
 *   <li>The decode thread from moonlight-common-c, which calls {@link #submitDecodeUnit}.</li>
 *   <li>The renderer thread, which dequeues output buffers and (outside balanced pacing) presents
 *       them.</li>
 *   <li>The Choreographer thread, in balanced pacing only, which presents on vsync.</li>
 *   <li>The UI thread, which constructs this object and drives {@link #setup} and {@link #stop}.</li>
 * </ol>
 */
public class MediaCodecDecoderRenderer extends VideoDecoderRenderer implements Choreographer.FrameCallback {

    // Debug switches for measuring decoder latency via onFrameRendered() instead of estimating it
    private static final boolean USE_FRAME_RENDER_TIME = false;
    private static final boolean FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false;

    // Decoder chosen for each codec at construction time, or null if there's no usable one
    private MediaCodecInfo avcDecoder;
    private MediaCodecInfo hevcDecoder;
    private MediaCodecInfo av1Decoder;

    // Codec-specific data (parameter sets) retained so they can be replayed after a codec
    // restart, since the host won't resend them unless asked
    private final ArrayList<byte[]> vpsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> spsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> ppsBuffers = new ArrayList<>();
    private boolean submittedCsd;
    private byte[] currentHdrMetadata;

    // Input buffer currently being filled by submitDecodeUnit(), held across calls because one
    // frame can arrive as several decode units
    private int nextInputBufferIndex = -1;
    private ByteBuffer nextInputBuffer;

    private Context context;
    private Activity activity;
    private MediaCodec videoDecoder;
    private Thread rendererThread;

    // Device quirks resolved from MediaCodecHelper; see the corresponding methods there
    private boolean needsSpsBitstreamFixup, isExynos4;
    private boolean adaptivePlayback, directSubmit, fusedIdrFrame;
    private boolean constrainedHighProfile;
    private boolean refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1;
    private byte optimalSlicesPerFrame;
    private boolean refFrameInvalidationActive;

    private int initialWidth, initialHeight;
    private int videoFormat;
    private SurfaceHolder renderTarget;
    // Set on the UI thread, read by the decode and renderer threads to wind themselves down
    private volatile boolean stopping;
    private CrashListener crashListener;
    private boolean reportedCrash;
    // Crashes in previous sessions, used to progressively disable risky features
    private int consecutiveCrashCount;
    private String glRenderer;
    private PerfOverlayListener perfListener;

    // The overlay text is built here rather than on the decode thread that produces the numbers.
    // Main looper because the listener updates a view, so this also removes the hop that
    // Game.onPerfUpdate would otherwise have to make.
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());

    // Codec recovery. Escalating strategies, tried in order of how much they disturb the stream:
    // flush keeps the codec configured, restart stops and starts it, reset rebuilds it entirely.
    private static final int CR_MAX_TRIES = 10;
    private static final int CR_RECOVERY_TYPE_NONE = 0;
    private static final int CR_RECOVERY_TYPE_FLUSH = 1;
    private static final int CR_RECOVERY_TYPE_RESTART = 2;
    private static final int CR_RECOVERY_TYPE_RESET = 3;
    private AtomicInteger codecRecoveryType = new AtomicInteger(CR_RECOVERY_TYPE_NONE);
    private final Object codecRecoveryMonitor = new Object();

    // Each thread that touches the MediaCodec object or any associated buffers must have a flag
    // here and must call doCodecRecoveryIfRequired() on a regular basis.
    private static final int CR_FLAG_INPUT_THREAD = 0x1;
    private static final int CR_FLAG_RENDER_THREAD = 0x2;
    private static final int CR_FLAG_CHOREOGRAPHER = 0x4;
    private static final int CR_FLAG_ALL = CR_FLAG_INPUT_THREAD | CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER;
    private int codecRecoveryThreadQuiescedFlags = 0;
    private int codecRecoveryAttempts = 0;

    private MediaFormat inputFormat;
    private MediaFormat outputFormat;
    private MediaFormat configuredFormat;

    private boolean needsBaselineSpsHack;
    private SeqParameterSet savedSps;

    // First exception seen, held back briefly in case a more informative one follows: the initial
    // failure is often a generic IllegalStateException while the real cause surfaces moments later
    private RendererException initialException;
    private long initialExceptionTimestamp;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    // How long the decoder may refuse to hand back an input buffer before we call it hung. The
    // user has been looking at a frozen picture for this whole time, so it cannot be generous.
    private static final int DECODER_HANG_THRESHOLD_MS = 5000;

    // Stats for the current one-second window, the previous one (what the overlay displays), and
    // the whole session (what the post-stream summary displays)
    private VideoStats activeWindowVideoStats;
    private VideoStats lastWindowVideoStats;
    private VideoStats globalVideoStats;
    private final TrafficStatsHelper trafficStats = new TrafficStatsHelper();

    private long lastTimestampUs;
    private int lastFrameNumber;
    // The stream's frame rate, not the display's, despite the name
    private int refreshRate;
    private volatile Display vsyncDisplay;
    private volatile int rendererTid;
    private PerformanceHintManager.Session perfHintSession;
    private long targetFrameTimeNanos;
    private PreferenceConfiguration prefs;

    // Decoded frames waiting for a vsync to present on, in balanced pacing mode only. Bounded at
    // two: enough to absorb jitter, few enough not to starve the decoder of output buffers or add
    // a frame of latency.
    private final OutputBufferRing outputBufferQueue = new OutputBufferRing(OUTPUT_BUFFER_QUEUE_LIMIT);
    private static final int OUTPUT_BUFFER_QUEUE_LIMIT = 2;
    private long lastRenderedFrameTimeNanos;
    private HandlerThread choreographerHandlerThread;
    private Handler choreographerHandler;

    private int numSpsIn;
    private int numPpsIn;
    private int numVpsIn;
    private int numFramesIn;
    private int numFramesOut;

    private MediaCodecInfo findAvcDecoder() {
        MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc");
        }
        return decoder;
    }

    /**
     * @return true if the decoder claims it can handle the configured resolution and frame rate.
     *         Uses the platform's performance points where the device publishes them, and falls
     *         back to the achievable frame rate range otherwise.
     */
    private boolean decoderCanMeetPerformancePoint(MediaCodecInfo.VideoCapabilities caps, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities.PerformancePoint targetPerfPoint = new MediaCodecInfo.VideoCapabilities.PerformancePoint(prefs.width, prefs.height, prefs.fps);
        List<MediaCodecInfo.VideoCapabilities.PerformancePoint> perfPoints = caps.getSupportedPerformancePoints();
        if (perfPoints != null) {
            for (MediaCodecInfo.VideoCapabilities.PerformancePoint perfPoint : perfPoints) {
                // If we find a performance point that covers our target, we're good to go
                if (perfPoint.covers(targetPerfPoint)) {
                    return true;
                }
            }

            // We had performance point data but none met the specified streaming settings
            return false;
        }

        // Fall-through to try the Android M API if there's no performance point data

        try {
            // We'll ask the decoder what it can do for us at this resolution and see if our
            // requested frame rate falls below or inside the range of achievable frame rates.
            Range<Double> fpsRange = caps.getAchievableFrameRatesFor(prefs.width, prefs.height);
            if (fpsRange != null) {
                return prefs.fps <= fpsRange.getUpper();
            }

            // Fall-through to try the Android L API if there's no performance point data
        } catch (IllegalArgumentException e) {
            // Video size not supported at any frame rate
            return false;
        }

        // As a last resort, we will use areSizeAndRateSupported() which is explicitly NOT a
        // performance metric, but it can work at least for the purpose of determining if
        // the codec is going to die when given a stream with the specified settings.
        return caps.areSizeAndRateSupported(prefs.width, prefs.height, prefs.fps);
    }

    /**
     * @return true if HEVC is the only one of the two that can sustain the configured settings,
     *         which is grounds for using a HEVC decoder that isn't otherwise whitelisted
     */
    private boolean decoderCanMeetPerformancePointWithHevcAndNotAvc(MediaCodecInfo hevcDecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(hevcCaps, prefs);
    }

    /** @return true if AV1 can sustain the configured settings where HEVC cannot */
    private boolean decoderCanMeetPerformancePointWithAv1AndNotHevc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo hevcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(hevcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
    }

    /** @return true if AV1 can sustain the configured settings where H.264 cannot */
    private boolean decoderCanMeetPerformancePointWithAv1AndNotAvc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
        MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();

        return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
    }

    /**
     * Chooses an HEVC decoder, or null to stay on H.264.
     *
     * <p>HEVC is not taken just because a decoder exists: many report support they can't deliver,
     * so a decoder has to be whitelisted in {@link MediaCodecHelper} or justified by something
     * that H.264 can't do — HDR, resolutions above 4K, an explicit user choice, or H.264 being
     * unable to meet the performance point.
     *
     * @param requestedHdr HDR was requested, which mandates HEVC Main10
     */
    private MediaCodecInfo findHevcDecoder(PreferenceConfiguration prefs, boolean requestedHdr) {
        // Don't return anything if H.264 is forced
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null;
        }

        // We don't try the first HEVC decoder. We'd rather fall back to hardware accelerated AVC instead
        //
        // We need HEVC Main profile, so we could pass that constant to findProbableSafeDecoder, however
        // some decoders (at least Qualcomm's Snapdragon 805) don't properly report support
        // for even required levels of HEVC.
        MediaCodecInfo hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);

        // Refuse decoders that are known to wedge mid-stream, ahead of the whitelist check below.
        // This is deliberately not one of the "not whitelisted, but justified" cases: HDR, over-4K
        // and an explicit user request can all override the whitelist, and none of them are worth
        // a stream that freezes. Falling back to H.264 is the only useful thing left to do.
        if (hevcDecoderInfo != null && MediaCodecHelper.isKnownBrokenHevcDecoder(hevcDecoderInfo)) {
            LimeLog.severe("Refusing HEVC on " + hevcDecoderInfo.getName() + ": this decoder is" +
                    " known to stop producing output mid-stream and never recover. Falling back to" +
                    " H.264. See moonlight-stream/moonlight-android#1504.");
            return null;
        }

        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - "+hevcDecoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder");
                }
                // HDR implies HEVC forced on, since HEVCMain10HDR10 is required for HDR.
                else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming");
                }
                // > 4K streaming also requires HEVC, so force it on there too.
                else if (prefs.width > 4096 || prefs.height > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming");
                }
                // Use HEVC if the H.264 decoder is unable to meet the performance point
                else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return hevcDecoderInfo;
    }

    /**
     * Chooses an AV1 decoder, or null. Opt-in only: AV1 hardware decoding is new enough that it
     * is never selected automatically, however capable the decoder claims to be.
     */
    private MediaCodecInfo findAv1Decoder(PreferenceConfiguration prefs) {
        // For now, don't use AV1 unless explicitly requested
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null;
        }

        MediaCodecInfo decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1);
        if (decoderInfo != null) {
            if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
                LimeLog.info("Found AV1 decoder, but it's not whitelisted - "+decoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) {
                    LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder");
                }
                // Use AV1 if the HEVC decoder is unable to meet the performance point
                else if (hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, hevcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                // Use AV1 if the H.264 decoder is unable to meet the performance point and we have no HEVC decoder
                else if (hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return decoderInfo;
    }

    /** Sets the surface frames are presented to. Must be called before {@link #setup}. */
    public void setRenderTarget(SurfaceHolder renderTarget) {
        this.renderTarget = renderTarget;
    }

    /**
     * Selects decoders and resolves device quirks, but does not create a codec — that happens in
     * {@link #setup}. Constructed early so the capabilities it discovers can shape the stream
     * configuration that is then negotiated with the host.
     *
     * @param consecutiveCrashCount crashes in previous sessions; risky features are disabled as
     *                              this rises, so a device that crashes repeatedly degrades into
     *                              a working configuration instead of failing forever
     * @param requestedHdr          HDR was requested, which forces HEVC
     * @param glRenderer            GL renderer string, used for GPU-specific quirks
     */
    public MediaCodecDecoderRenderer(Activity activity, PreferenceConfiguration prefs,
                                     CrashListener crashListener, int consecutiveCrashCount,
                                     boolean requestedHdr,
                                     String glRenderer, PerfOverlayListener perfListener) {
        //dumpDecoders();

        this.context = activity;
        this.activity = activity;
        this.prefs = prefs;
        this.crashListener = crashListener;
        this.consecutiveCrashCount = consecutiveCrashCount;
        this.glRenderer = glRenderer;
        this.perfListener = perfListener;

        this.activeWindowVideoStats = new VideoStats();
        this.lastWindowVideoStats = new VideoStats();
        this.globalVideoStats = new VideoStats();

        avcDecoder = findAvcDecoder();
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: "+avcDecoder.getName());
        }
        else {
            LimeLog.warning("No AVC decoder found");
        }

        hevcDecoder = findHevcDecoder(prefs, requestedHdr);
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: "+hevcDecoder.getName());
        }
        else {
            LimeLog.info("No HEVC decoder found");
        }

        av1Decoder = findAv1Decoder(prefs);
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: "+av1Decoder.getName());
        }
        else {
            LimeLog.info("No AV1 decoder found");
        }

        // Set attributes that are queried in getCapabilities(). This must be done here
        // because getCapabilities() may be called before setup() in current versions of the common
        // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
        int avcOptimalSlicesPerFrame = 0;
        int hevcOptimalSlicesPerFrame = 0;
        if (avcDecoder != null) {
            directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avcDecoder.getName());
            refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avcDecoder.getName(), prefs.height);
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder.getName());

            if (directSubmit) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use direct submit");
            }
            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use reference frame invalidation for AVC");
            }
            LimeLog.info("Decoder "+avcDecoder.getName()+" wants "+avcOptimalSlicesPerFrame+" slices per frame");
        }

        if (hevcDecoder != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder);
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder.getName());

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder "+hevcDecoder.getName()+" will use reference frame invalidation for HEVC");
            }

            LimeLog.info("Decoder "+hevcDecoder.getName()+" wants "+hevcOptimalSlicesPerFrame+" slices per frame");
        }

        if (av1Decoder != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder);

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder "+av1Decoder.getName()+" will use reference frame invalidation for AV1");
            }
        }

        // Use the larger of the two slices per frame preferences
        optimalSlicesPerFrame = (byte)Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame);
        LimeLog.info("Requesting "+optimalSlicesPerFrame+" slices per frame");

        // All three codecs, not just AVC and HEVC: whichever one is actually streaming is the
        // one whose RFI needs withdrawing, and leaving AV1 set means this backs off everything
        // except the codec that just crashed.
        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = refFrameInvalidationHevc = refFrameInvalidationAv1 = false;
            LimeLog.warning("Disabling RFI due to previous crash");
        }
    }

    /** @return true if an HEVC decoder was selected; see {@link #findHevcDecoder} for the criteria */
    public boolean isHevcSupported() {
        return hevcDecoder != null;
    }

    /** @return true if an H.264 decoder was found. False here means streaming is impossible. */
    public boolean isAvcSupported() {
        return avcDecoder != null;
    }

    /** @return true if the selected HEVC decoder supports Main10 HDR10, a prerequisite for HDR */
    public boolean isHevcMain10Hdr10Supported() {
        if (hevcDecoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                LimeLog.info("HEVC decoder "+hevcDecoder.getName()+" supports HEVC Main10 HDR10");
                return true;
            }
        }

        return false;
    }

    /** @return true if an AV1 decoder was selected, which requires the user to have opted in */
    public boolean isAv1Supported() {
        return av1Decoder != null;
    }

    /** @return true if the selected AV1 decoder supports Main 10 HDR10 */
    public boolean isAv1Main10Supported() {
        if (av1Decoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : av1Decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder "+av1Decoder.getName()+" supports AV1 Main 10 HDR10");
                return true;
            }
        }

        return false;
    }

    /** @return the colorspace to ask the host to encode in, as a {@code MoonBridge.COLORSPACE_*} */
    public int getPreferredColorSpace() {
        // Default to Rec 709 which is probably better supported on modern devices.
        //
        // We are sticking to Rec 601 on older devices unless the device has an HEVC decoder
        // to avoid possible regressions (and they are < 5% of installed devices). If we have
        // an HEVC decoder, we will use Rec 709 (even for H.264) since we can't choose a
        // colorspace by codec (and it's probably safe to say a SoC with HEVC decoding is
        // plenty modern enough to handle H.264 VUI colorspace info).
        return MoonBridge.COLORSPACE_REC_709;
    }

    /**
     * @return full or limited range, per the user's preference. Getting this wrong shows up as
     *         washed out or crushed blacks, so it is negotiated rather than assumed.
     */
    public int getPreferredColorRange() {
        if (prefs.fullRange) {
            return MoonBridge.COLOR_RANGE_FULL;
        }
        else {
            return MoonBridge.COLOR_RANGE_LIMITED;
        }
    }

    /** @return the {@code MoonBridge.VIDEO_FORMAT_*} currently being decoded */
    public int getActiveVideoFormat() {
        return this.videoFormat;
    }

    /**
     * Builds the {@link MediaFormat} common to all codecs: dimensions, frame rate, adaptive
     * playback bounds and colour information.
     *
     * <p>Colour keys are only set when the stream is SDR. In HDR the decoder detects transitions
     * in the bitstream itself, and hardcoding them here would fight that.
     */
    private MediaFormat createBaseMediaFormat(String mimeType) {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight);

        // Hint the stream's frame rate so the decoder can size its internal buffering.
        // (Upstream gated this on API 23+; unconditional now that minSdk is 30.)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);

        // Populate keys for adaptive playback
        if (adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth);
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight);
        }

        // Colour options on the MediaFormat, available since Android 7.0
        videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL ?
                MediaFormat.COLOR_RANGE_FULL : MediaFormat.COLOR_RANGE_LIMITED);

        // If the stream is HDR-capable, the decoder will detect transitions in color standards
        // rather than us hardcoding them into the MediaFormat.
        if ((getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
            // Set color format keys when not in HDR mode, since we know they won't change
            videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            switch (getPreferredColorSpace()) {
                case MoonBridge.COLORSPACE_REC_601:
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                    break;
                case MoonBridge.COLORSPACE_REC_709:
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                    break;
                case MoonBridge.COLORSPACE_REC_2020:
                    videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                    break;
            }
        }

        return videoFormat;
    }

    /**
     * Configures the codec against the render surface and starts it.
     *
     * <p>Also resets the codec-specific data state, since a freshly configured codec has to be
     * given the parameter sets again before it will decode anything.
     *
     * @throws IllegalStateException if the codec rejects the format, which the caller turns into
     *                               a fallback or a recovery attempt
     */
    private void configureAndStartDecoder(MediaFormat format) {
        // Set HDR metadata if present
        if (currentHdrMetadata != null) {
            ByteBuffer hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer hdrMetadata = ByteBuffer.wrap(currentHdrMetadata).order(ByteOrder.LITTLE_ENDIAN);

            // Create a HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
            hdrStaticInfo.put((byte) 0); // Metadata type
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // RX
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // RY
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // GX
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // GY
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // BX
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // BY
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // White X
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // White Y
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max mastering luminance
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // Min mastering luminance
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max content luminance
            hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max frame average luminance

            hdrStaticInfo.rewind();
            format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo);
        }
        else {
            format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO);
        }

        LimeLog.info("Configuring with format: "+format);

        videoDecoder.configure(format, renderTarget.getSurface(), null, 0);

        configuredFormat = format;

        // After reconfiguration, we must resubmit CSD buffers
        submittedCsd = false;
        vpsBuffers.clear();
        spsBuffers.clear();
        ppsBuffers.clear();

        // This will contain the actual accepted input format attributes
        inputFormat = videoDecoder.getInputFormat();
        LimeLog.info("Input format: "+inputFormat);

        videoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        // Start the decoder
        videoDecoder.start();
    }

    /**
     * Attempts one codec configuration, releasing the codec again if it fails.
     *
     * @param throwOnCodecError rethrow instead of returning false. Used when the caller has run
     *                          out of fallback formats and wants the real exception surfaced.
     * @return true if the codec is configured and running
     */
    private boolean tryConfigureDecoder(MediaCodecInfo selectedDecoderInfo, MediaFormat format, boolean throwOnCodecError) {
        boolean configured = false;
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.getName());
            configureAndStartDecoder(format);
            LimeLog.info("Using codec " + selectedDecoderInfo.getName() + " for hardware decoding " + format.getString(MediaFormat.KEY_MIME));
            configured = true;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IllegalStateException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw new RuntimeException(e);
            }
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder.release();
                videoDecoder = null;
            }
        }
        return configured;
    }

    /**
     * Picks the decoder for the negotiated video format, resolves its quirks, and configures it,
     * retrying with progressively more conservative formats if the first attempt is rejected.
     *
     * @param throwOnCodecError propagate codec exceptions instead of reporting failure
     * @return 0 on success, or a negative code identifying which decoder was missing or which
     *         format was unusable
     */
    public int initializeDecoder(boolean throwOnCodecError) {
        String mimeType;
        MediaCodecInfo selectedDecoderInfo;

        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            mimeType = "video/avc";
            selectedDecoderInfo = avcDecoder;

            if (avcDecoder == null) {
                LimeLog.severe("No available AVC decoder!");
                return -1;
            }

            if (initialWidth > 4096 || initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC");
                return -1;
            }

            // These fixups only apply to H264 decoders
            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.getName());
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.getName());
            constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.getName());
            isExynos4 = MediaCodecHelper.isExynos4Device();
            if (needsSpsBitstreamFixup) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs SPS bitstream restrictions fixup");
            }
            if (needsBaselineSpsHack) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs baseline SPS hack");
            }
            if (constrainedHighProfile) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs constrained high profile");
            }
            if (isExynos4) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" is on Exynos 4");
            }

            refFrameInvalidationActive = refFrameInvalidationAvc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc";
            selectedDecoderInfo = hevcDecoder;

            if (hevcDecoder == null) {
                LimeLog.severe("No available HEVC decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationHevc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01";
            selectedDecoderInfo = av1Decoder;

            if (av1Decoder == null) {
                LimeLog.severe("No available AV1 decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationAv1;
        }
        else {
            // Unknown format
            LimeLog.severe("Unknown format");
            return -3;
        }

        adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType);
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType);

        for (int tryNumber = 0;; tryNumber++) {
            LimeLog.info("Decoder configuration try: "+tryNumber);

            MediaFormat mediaFormat = createBaseMediaFormat(mimeType);

            // This will try low latency options until we find one that works (or we give up).
            boolean newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(mediaFormat, selectedDecoderInfo, tryNumber);

            // Throw the underlying codec exception on the last attempt if the caller requested it
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                // Success!
                break;
            }

            if (!newFormat) {
                // We couldn't even configure a decoder without any low latency options
                return -5;
            }
        }

        // Registered whenever this is a debug build, not only when the overlay is on. The listener
        // has to be attached before the codec starts to be reliable across devices, so deciding
        // later is not an option - and counting from stream start means turning the overlay on
        // mid-stream shows real history rather than starting from zero. Release attaches no
        // listener at all, so the per-frame callback is only ever delivered in a build meant for
        // diagnosis. See CLAUDE.md on keeping per-frame instrumentation out of release.
        if (BuildConfig.DEBUG || USE_FRAME_RENDER_TIME) {
            videoDecoder.setOnFrameRenderedListener(new MediaCodec.OnFrameRenderedListener() {
                @Override
                public void onFrameRendered(MediaCodec mediaCodec, long presentationTimeUs, long renderTimeNanos) {
                    if (USE_FRAME_RENDER_TIME) {
                        long delta = (renderTimeNanos / 1000000L) - (presentationTimeUs / 1000);
                        if (delta >= 0 && delta < 1000) {
                            activeWindowVideoStats.totalTimeMs += delta;
                        }
                    }

                    if (BuildConfig.DEBUG) {
                        recordPresentedFrame(renderTimeNanos);
                    }
                }
            }, null);
        }

        return 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called by moonlight-common-c once the stream format has been negotiated with the host.
     * Codec errors are swallowed here so that a failure surfaces as a return code the connection
     * logic can act on, rather than as an exception out of native code.
     */
    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.initialWidth = width;
        this.initialHeight = height;
        this.videoFormat = format;
        this.refreshRate = redrawRate;

        return initializeDecoder(false);
    }

    /**
     * Rendezvous point for codec recovery, and the reason every codec-touching thread has a
     * {@code CR_FLAG_*} of its own.
     *
     * <p>{@link MediaCodec} cannot be recovered while other threads are inside it, so recovery is
     * a barrier: each thread marks itself quiesced here and blocks, and whichever thread is last
     * in performs the recovery on everyone's behalf before waking the others. Recovery escalates
     * — flush, restart, reset, and finally recreating the decoder from scratch — with each step
     * falling through to the next if it throws.
     *
     * <p>Deliberately does not check {@code stopping}: bailing out mid-barrier would leave the
     * already-quiesced threads waiting for a signal that never comes.
     *
     * @param quiescenceFlag the calling thread's {@code CR_FLAG_*}
     * @return true if recovery took place, meaning the caller's codec state is now invalid and
     *         any buffer it was holding is gone
     */
    // All threads that interact with the MediaCodec instance must call this function regularly!
    private boolean doCodecRecoveryIfRequired(int quiescenceFlag) {
        // NB: We cannot check 'stopping' here because we could end up bailing in a partially
        // quiesced state that will cause the quiesced threads to never wake up.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            // Common case
            return false;
        }

        // We need some sort of recovery, so quiesce all threads before starting that
        synchronized (codecRecoveryMonitor) {
            if (choreographerHandlerThread == null) {
                // If we have no choreographer thread, we can just mark that as quiesced right now.
                codecRecoveryThreadQuiescedFlags |= CR_FLAG_CHOREOGRAPHER;
            }

            codecRecoveryThreadQuiescedFlags |= quiescenceFlag;

            // This is the final thread to quiesce, so let's perform the codec recovery now.
            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                // Input and output buffers are invalidated by stop() and reset().
                nextInputBuffer = null;
                nextInputBufferIndex = -1;
                outputBufferQueue.clear();

                // If we just need a flush, do so now with all threads quiesced.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder");
                    try {
                        videoDecoder.flush();
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART);
                    }
                }

                // We don't count flushes as codec recovery attempts
                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    codecRecoveryAttempts++;
                    LimeLog.info("Codec recovery attempt: "+codecRecoveryAttempts);
                }

                // For "recoverable" exceptions, we can just stop, reconfigure, and restart.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                    LimeLog.warning("Trying to restart decoder after CodecException");
                    try {
                        videoDecoder.stop();
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET);
                    }
                }

                // For "non-recoverable" exceptions on L+, we can call reset() to recover
                // without having to recreate the entire decoder again.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to reset decoder after CodecException");
                    try {
                        videoDecoder.reset();
                        configureAndStartDecoder(configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the reset, we'll have to resort to
                        // releasing and recreating the decoder now.
                    }
                }

                // If we _still_ haven't managed to recover, go for the nuclear option and just
                // throw away the old decoder and reinitialize a new one from scratch.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to recreate decoder after CodecException");
                    videoDecoder.release();

                    try {
                        int err = initializeDecoder(true);
                        if (err != 0) {
                            throw new IllegalStateException("Decoder reset failed: " + err);
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        // If we failed to recover after all of these attempts, just crash
                        if (!reportedCrash) {
                            reportedCrash = true;
                            crashListener.notifyCrash(e);
                        }
                        throw new RendererException(this, e);
                    }
                }

                // Wake all quiesced threads and allow them to begin work again
                codecRecoveryThreadQuiescedFlags = 0;
                codecRecoveryMonitor.notifyAll();
            }
            else {
                // If we haven't quiesced all threads yet, wait to be signalled after recovery.
                // The final thread to be quiesced will handle the codec recovery.
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try {
                        LimeLog.info("Waiting to quiesce decoder threads: "+codecRecoveryThreadQuiescedFlags);
                        codecRecoveryMonitor.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();

                        // InterruptedException clears the thread's interrupt status. Since we can't
                        // handle that here, we will re-interrupt the thread to set the interrupt
                        // status back to true.
                        Thread.currentThread().interrupt();

                        break;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Classifies a codec exception and schedules recovery if it looks survivable.
     *
     * <p>Recovery is capped at {@link #CR_MAX_TRIES}, past which the exception is treated as
     * fatal — a codec that has failed ten times is not going to start working, and retrying
     * forever would leave the user watching a frozen frame instead of an error.
     *
     * @return true if the exception was transient and the caller should simply carry on
     */
    // Returns true if the exception is transient
    private boolean handleDecoderException(IllegalStateException e) {
        // Eat decoder exceptions if we're in the process of stopping
        if (stopping) {
            return false;
        }

        if (e instanceof CodecException) {
            CodecException codecExc = (CodecException) e;

            if (codecExc.isTransient()) {
                // We'll let transient exceptions go
                LimeLog.warning(codecExc.getDiagnosticInfo());
                return true;
            }

            LimeLog.severe(codecExc.getDiagnosticInfo());

            // We can attempt a recovery or reset at this stage to try to start decoding again
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                // If the exception is non-recoverable or we already require a reset, perform a reset.
                // If we have no prior unrecoverable failure, we will try a restart instead.
                if (codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder requires restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder flush promoted to restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }
                else if (!codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder requires reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }

                // The recovery will take place when all threads reach doCodecRecoveryIfRequired().
                return false;
            }
        }
        else {
            // IllegalStateException was primarily used prior to the introduction of CodecException.
            // Recovery from this requires a full decoder reset.
            //
            // NB: CodecException is an IllegalStateException, so we must check for it first.
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder requires reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder flush promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder restart promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                }

                return false;
            }
        }

        // Only throw if we're not in the middle of codec recovery
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            //
            // There seems to be a race condition with decoder/surface teardown causing some
            // decoders to to throw IllegalStateExceptions even before 'stopping' is set.
            // To workaround this while allowing real exceptions to propagate, we will eat the
            // first exception. If we are still receiving exceptions 3 seconds later, we will
            // throw the original exception again.
            //
            if (initialException != null) {
                // This isn't the first time we've had an exception processing video
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    // It's been over 3 seconds and we're still getting exceptions. Throw the original now.
                    if (!reportedCrash) {
                        reportedCrash = true;
                        crashListener.notifyCrash(initialException);
                    }
                    throw initialException;
                }
            }
            else {
                // This is the first exception we've hit
                initialException = new RendererException(this, e);
                initialExceptionTimestamp = SystemClock.uptimeMillis();
            }
        }

        // Not transient
        return false;
    }

    /**
     * Vsync callback, used in balanced frame pacing only.
     *
     * <p>Presents at most one queued frame per vsync and re-arms itself. Presenting on the vsync
     * rather than as soon as frames arrive is what removes the microstutter you get when the
     * stream rate doesn't divide into the display rate, at the cost of up to one frame of
     * latency — which is why it isn't the default.
     *
     * <p>Runs on the dedicated Choreographer thread; see {@link #startChoreographerThread()}.
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        // Do nothing if we're stopping
        if (stopping) {
            return;
        }

        // Only read the clock when there's a hint session to report to, so devices without
        // ADPF (the Shield, on Android 11) pay nothing for this.
        long workStartNanos = (perfHintSession != null) ? System.nanoTime() : 0;

        // Queried per frame on purpose: see startChoreographerThread(). getDisplay() can
        // return null where the old getDefaultDisplay() could not, so tolerate that.
        Display display = vsyncDisplay;
        if (display != null) {
            frameTimeNanos -= display.getAppVsyncOffsetNanos();
        }

        // Don't render unless a new frame is due. This prevents microstutter when streaming
        // at a frame rate that doesn't match the display (such as 60 FPS on 120 Hz).
        long actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos;
        long expectedFrameTimeDeltaNs = 800000000 / refreshRate; // within 80% of the next frame
        if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
            // Render up to one frame when in frame pacing mode.
            //
            // NB: Since the queue limit is 2, we won't starve the decoder of output buffers
            // by holding onto them for too long. This also ensures we will have that 1 extra
            // frame of buffer to smooth over network/rendering jitter.
            int nextOutputBuffer = outputBufferQueue.poll();
            if (nextOutputBuffer != OutputBufferRing.EMPTY) {
                try {
                    videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);

                    lastRenderedFrameTimeNanos = frameTimeNanos;
                    activeWindowVideoStats.totalFramesRendered++;
                } catch (IllegalStateException ignored) {
                    try {
                        // Try to avoid leaking the output buffer by releasing it without rendering
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, false);
                    } catch (IllegalStateException e) {
                        // This will leak nextOutputBuffer, but there's really nothing else we can do
                        e.printStackTrace();
                        handleDecoderException(e);
                    }
                }
            }
        }

        // Attempt codec recovery even if we have nothing to render right now. Recovery can still
        // be required even if the codec died before giving any output.
        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);

        // Tell the scheduler how long this frame actually took, so it can size clocks to our
        // deadline. Reported before requesting the next callback so it reflects render work
        // only. A non-positive duration is rejected by the API, so floor it at 1 ns.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && perfHintSession != null) {
            long workDurationNanos = System.nanoTime() - workStartNanos;
            perfHintSession.reportActualWorkDuration(Math.max(1, workDurationNanos));
        }

        // Request another callback for next frame
        Choreographer.getInstance().postFrameCallback(this);
    }

    /**
     * Creates an ADPF performance hint session covering the threads on the frame-critical
     * path, telling the scheduler our per-frame deadline so it holds clocks there instead of
     * ramping reactively.
     *
     * Called from whichever thread does the presenting: the Choreographer thread in BALANCED
     * pacing, the renderer thread in every other mode. Process.myTid() therefore identifies
     * the caller, and the renderer TID is folded in when they differ.
     *
     * PerformanceHintManager is API 31, so this benefits the Homatics Box R 4K (Android 14)
     * and does nothing on the Shield TV (Android 11). Failure is non-fatal: the session
     * simply stays null and both report sites skip the clock read entirely.
     */
    private void createPerformanceHintSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }

        PerformanceHintManager hintManager = activity.getSystemService(PerformanceHintManager.class);
        if (hintManager == null) {
            return;
        }

        // Include the renderer thread if it has published its TID and isn't us. When called
        // from the renderer thread itself these are the same TID, and passing a duplicate
        // would be rejected.
        int selfTid = Process.myTid();
        int rTid = rendererTid;
        int[] tids = (rTid != 0 && rTid != selfTid)
                ? new int[] { selfTid, rTid }
                : new int[] { selfTid };

        // Target one stream frame interval. refreshRate here is the *stream* frame rate
        // passed to setup(), not the display's.
        targetFrameTimeNanos = 1000000000L / refreshRate;

        try {
            perfHintSession = hintManager.createHintSession(tids, targetFrameTimeNanos);
            if (perfHintSession != null) {
                LimeLog.info("Created ADPF hint session with target " +
                        (targetFrameTimeNanos / 1000000.0) + " ms for " + tids.length + " threads");
            }
        } catch (Exception e) {
            // Device may not implement the hint API even on a supported API level
            LimeLog.warning("Unable to create ADPF hint session: " + e.getMessage());
            perfHintSession = null;
        }
    }

    /**
     * Starts the vsync-driven presentation thread, in balanced pacing mode only. In every other
     * mode the renderer thread presents directly and this returns immediately.
     */
    private void startChoreographerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            // Not using Choreographer in this pacing mode
            return;
        }

        // We use a separate thread to avoid any main thread delays from delaying rendering
        choreographerHandlerThread = new HandlerThread("Video - Choreographer", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        choreographerHandlerThread.start();

        // Start the frame callbacks
        choreographerHandler = new Handler(choreographerHandlerThread.getLooper());

        // Resolve the Display handle once. The handle is stable; the DisplayInfo behind it is
        // refreshed by the platform on every getter call, which is what makes the per-frame
        // getAppVsyncOffsetNanos() in doFrame() correct across display mode changes.
        //
        // Do NOT cache the offset value itself. It is a property of the active display
        // configuration, and Android models refresh-rate switches (60<->120,
        // Surface.setFrameRate(), HDR mode changes on some TVs) as mode changes. Caching it
        // and refreshing via DisplayManager.DisplayListener does not work: that callback is
        // posted to a Handler, so doFrame() would keep applying a stale offset for the frames
        // between the actual switch and the callback running - i.e. wrong exactly during a
        // VRR transition. The per-frame call is a lock plus a cached lookup, which is
        // nothing against a frame budget.
        vsyncDisplay = activity.getDisplay();
        if (vsyncDisplay == null) {
            vsyncDisplay = activity.getSystemService(DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY);
        }

        choreographerHandler.post(new Runnable() {
            @Override
            public void run() {
                createPerformanceHintSession();
                Choreographer.getInstance().postFrameCallback(MediaCodecDecoderRenderer.this);
            }
        });
    }

    /**
     * Starts the thread that drains decoded frames.
     *
     * <p>Outside balanced pacing it also presents them, dropping everything but the newest frame
     * so the display always shows the most recent one the decoder has produced. In balanced
     * pacing it only queues them for {@link #doFrame} to present on vsync.
     */
    private void startRendererThread()
    {
        rendererThread = new Thread() {
            @Override
            public void run() {
                // Thread.setPriority() below only sets the Java priority, which has little
                // effect on Android. Set the real scheduler priority here instead.
                //
                // URGENT_DISPLAY (-8) rather than DISPLAY (-4): this thread dequeues and
                // presents every frame, so it is the present path. NB this must be verified
                // on device - raising it too far can starve the decoder's own threads and
                // make frame times worse rather than better.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

                // Publish our TID so the ADPF hint session can include this thread
                rendererTid = Process.myTid();

                // In every pacing mode except BALANCED this thread does the presenting, so
                // the hint session has to be created here. It used to be created only from
                // startChoreographerThread(), which returns early unless pacing is BALANCED -
                // meaning ADPF was dead code under the default 'latency' pacing.
                if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                    createPerformanceHintSession();
                }

                BufferInfo info = new BufferInfo();
                while (!stopping) {
                    try {
                        // Try to output a frame
                        int outIndex = videoDecoder.dequeueOutputBuffer(info, 50000);
                        if (outIndex >= 0) {
                            // Time the drain-and-present cycle for ADPF. Only read the clock
                            // when there is a session to report to, so devices without ADPF
                            // pay nothing. The dequeue above is excluded deliberately: it
                            // blocks waiting for the decoder and is not our work.
                            long workStartNanos = (perfHintSession != null) ? System.nanoTime() : 0;

                            long presentationTimeUs = info.presentationTimeUs;
                            int lastIndex = outIndex;

                            numFramesOut++;

                            // Render the latest frame now if frame pacing isn't in balanced mode
                            if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                                // Get the last output buffer in the queue
                                while ((outIndex = videoDecoder.dequeueOutputBuffer(info, 0)) >= 0) {
                                    videoDecoder.releaseOutputBuffer(lastIndex, false);

                                    numFramesOut++;

                                    lastIndex = outIndex;
                                    presentationTimeUs = info.presentationTimeUs;
                                }

                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                                    // In max smoothness or cap FPS mode, we want to never drop frames
                                    // Use a PTS that will cause this frame to never be dropped
                                    videoDecoder.releaseOutputBuffer(lastIndex, 0);
                                }
                                else {
                                    // Use a PTS that will cause this frame to be dropped if another comes in within
                                    // the same V-sync period
                                    videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());
                                }

                                activeWindowVideoStats.totalFramesRendered++;

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && perfHintSession != null) {
                                    perfHintSession.reportActualWorkDuration(
                                            Math.max(1, System.nanoTime() - workStartNanos));
                                }
                            }
                            else {
                                // For balanced frame pacing case, the Choreographer callback will handle rendering.
                                // We just put all frames into the output buffer queue and let it handle things.

                                // Discard the oldest buffer if we've exceeded our limit.
                                //
                                // NB: We have to do this on the producer side because the consumer may not
                                // run for a while (if there is a huge mismatch between stream FPS and display
                                // refresh rate).
                                //
                                // Evicting and inserting are one call, so there is no window between
                                // them for the consumer to empty the ring. See OutputBufferRing for what
                                // used to go wrong here.
                                int evictedIndex = outputBufferQueue.offerEvictingOldest(lastIndex);
                                if (evictedIndex != OutputBufferRing.EMPTY) {
                                    videoDecoder.releaseOutputBuffer(evictedIndex, false);
                                }
                            }

                            // Add delta time to the totals (excluding probable outliers).
                            //
                            // Measured against the submit time we recorded ourselves, not against
                            // the presentation timestamp: the PTS carries moonlight-common-c's
                            // clock, and subtracting it from SystemClock put the result in no
                            // timebase at all. Every sample then failed the sanity check below and
                            // the overlay reported a flat 0.00 ms while the decoder was really
                            // taking several milliseconds a frame - and kept reporting 0.00
                            // through a decoder hang, which is when the number mattered most.
                            long delta = (BuildConfig.DEBUG && perfMetricsEnabled)
                                    ? takeDecodeStartDelta(presentationTimeUs) : -1;
                            if (delta >= 0 && delta < 1000) {
                                activeWindowVideoStats.decoderTimeMs += delta;
                                if (!USE_FRAME_RENDER_TIME) {
                                    activeWindowVideoStats.totalTimeMs += delta;
                                }
                            }
                        } else {
                            switch (outIndex) {
                                case MediaCodec.INFO_TRY_AGAIN_LATER:
                                    break;
                                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                                    LimeLog.info("Output format changed");
                                    outputFormat = videoDecoder.getOutputFormat();
                                    LimeLog.info("New output format: " + outputFormat);
                                    break;
                                default:
                                    break;
                            }
                        }
                    } catch (IllegalStateException e) {
                        handleDecoderException(e);
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
                    }
                }
            }
        };
        rendererThread.setName("Video - Renderer (MediaCodec)");
        rendererThread.setPriority(Thread.NORM_PRIORITY + 2);
        rendererThread.start();
    }

    // Whether the performance overlay is on screen, refreshed on the stats tick below. Read from
    // the frame paths, written from the decode thread, hence volatile.
    private volatile boolean perfMetricsEnabled;

    // Presentation gaps, measured from the codec's own render timestamps. Debug builds only.
    //
    // This is the counter that catches a frozen picture. "Rendering frame rate" counts frames
    // released to the surface, so it reported a healthy 60.02 FPS at a completely frozen screen
    // while the decoder discarded every frame it was handed. Render timestamps say what actually
    // reached the display.
    //
    // Written from the codec's callback thread and read by the stats tick, so both are volatile.
    // Counters rather than a log line per gap: logging here would mean up to sixty writes a second
    // on a callback thread during precisely the stall being diagnosed.
    private long lastPresentedFrameNanos;
    private volatile int presentationGapCount;
    private volatile long worstPresentationGapNanos;

    /**
     * Notes how long the display went without a new frame. Called once per presented frame on the
     * codec's callback thread.
     */
    private void recordPresentedFrame(long renderTimeNanos) {
        if (lastPresentedFrameNanos != 0) {
            long gapNanos = renderTimeNanos - lastPresentedFrameNanos;

            // Two frame intervals: one missed frame is noise at this granularity, and the
            // interval has to come from the stream rate rather than a constant because
            // refreshRate is whatever the stream negotiated.
            if (refreshRate > 0 && gapNanos > (2 * 1000000000L / refreshRate)) {
                presentationGapCount++;

                if (gapNanos > worstPresentationGapNanos) {
                    worstPresentationGapNanos = gapNanos;
                }
            }
        }

        lastPresentedFrameNanos = renderTimeNanos;
    }

    // Submit times for frames the decoder still holds, so decode latency can be measured in one
    // clock. Debug builds only, and only while the overlay is up - see CLAUDE.md on keeping
    // per-frame instrumentation out of release. Sized well past any sane decoder's depth and never
    // allocated on the hot path: both operations are a bounded walk over a fixed array, which
    // costs less than the map lookup the obvious implementation would need.
    private static final int DECODE_START_SLOTS = 16;
    private final long[] decodeStartPtsUs = new long[DECODE_START_SLOTS];
    private final long[] decodeStartUptimeMs = new long[DECODE_START_SLOTS];
    private int decodeStartPos;

    {
        // -1 rather than the default 0, so an empty slot can never match a real timestamp.
        java.util.Arrays.fill(decodeStartPtsUs, -1);
    }

    /** Records when a frame went into the decoder, keyed by the timestamp it was submitted with. */
    private void recordDecodeStart(long timestampUs) {
        decodeStartPtsUs[decodeStartPos] = timestampUs;
        decodeStartUptimeMs[decodeStartPos] = SystemClock.uptimeMillis();
        decodeStartPos = (decodeStartPos + 1) % DECODE_START_SLOTS;
    }

    /**
     * @return milliseconds the decoder held this frame, or -1 if its submit time is no longer
     *         known - which happens after a flush, or if the decoder buffers more frames than
     *         there are slots. Callers treat -1 as "no sample", so it is simply not counted.
     */
    private long takeDecodeStartDelta(long timestampUs) {
        for (int i = 0; i < DECODE_START_SLOTS; i++) {
            if (decodeStartPtsUs[i] == timestampUs) {
                // Clear the slot so a stale entry can't match a later frame that happens to
                // reuse the timestamp after a flush resets the sequence.
                decodeStartPtsUs[i] = -1;
                return SystemClock.uptimeMillis() - decodeStartUptimeMs[i];
            }
        }

        return -1;
    }

    /**
     * Obtains an input buffer to write the next decode unit into, blocking until one is free.
     *
     * <p>A decoder that stops returning input buffers is hung, and is tolerated only briefly:
     * the user is watching a frozen picture. The wait is therefore bounded by
     * {@link #DECODER_HANG_THRESHOLD_MS} rather than running until {@code stopping} is set.
     * Without that bound the wait was unbounded and the hang check below sat after it, so a
     * genuine hang spun here forever and was never reported — the report could only fire once
     * the user gave up and quit, by which time it was indistinguishable from ordinary shutdown.
     *
     * @return true if {@link #nextInputBuffer} is ready to be filled
     */
    private boolean fetchNextInputBuffer() {
        long startTime;
        boolean codecRecovered;

        if (nextInputBuffer != null) {
            // We already have an input buffer
            return true;
        }

        startTime = SystemClock.uptimeMillis();

        try {
            // If we don't have an input buffer index yet, fetch one now.
            //
            // Bounded by the hang threshold so a decoder that never returns a buffer falls out
            // of here and gets reported while the stream is still up. Each iteration has
            // already blocked 10 ms inside dequeueInputBuffer(), so the clock read costs
            // nothing measurable and never runs at all in the steady state - the common case
            // returns on the first call.
            while (nextInputBufferIndex < 0 && !stopping) {
                nextInputBufferIndex = videoDecoder.dequeueInputBuffer(10000);

                if (nextInputBufferIndex < 0 &&
                        SystemClock.uptimeMillis() - startTime >= DECODER_HANG_THRESHOLD_MS) {
                    break;
                }
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                // Using the new getInputBuffer() API on Lollipop allows
                // the framework to do some performance optimizations for us
                nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                if (nextInputBuffer == null) {
                    // According to the Android docs, getInputBuffer() can return null "if the
                    // index is not a dequeued input buffer". I don't think this ever should
                    // happen but if it does, let's try to get a new input buffer next time.
                    nextInputBufferIndex = -1;
                }
            }
        } catch (IllegalStateException e) {
            handleDecoderException(e);
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        // prepareForStop() deliberately breaks the wait above, so reaching here with 'stopping'
        // set says nothing about the decoder's health. A codec backpressured for several
        // seconds - after heavy packet loss, say - that the user then quits would otherwise
        // clear the hang threshold on the way out and record a crash that never happened.
        // Bail before the timing checks so teardown does not log a long-dequeue warning either.
        if (stopping) {
            return false;
        }

        int deltaMs = (int)(SystemClock.uptimeMillis() - startTime);

        if (deltaMs >= 20) {
            LimeLog.warning("Dequeue input buffer ran long: " + deltaMs + " ms");
        }

        if (nextInputBuffer == null) {
            // The decoder has refused us a buffer for the whole threshold and no other exception
            // was reported, so call it hung.
            if (deltaMs >= DECODER_HANG_THRESHOLD_MS && initialException == null) {
                DecoderHungException decoderHungException = new DecoderHungException(deltaMs);
                if (!reportedCrash) {
                    reportedCrash = true;
                    crashListener.notifyCrash(decoderHungException);
                }
                throw new RendererException(this, decoderHungException);
            }

            return false;
        }

        return true;
    }

    /** {@inheritDoc} Starts the presentation threads; the codec itself is already running. */
    @Override
    public void start() {
        startRendererThread();
        startChoreographerThread();
    }

    /**
     * First half of teardown: signals the threads to wind down and releases the resources that
     * reference them, without touching the codec itself.
     *
     * <p>Separate from {@link #stop()} because moonlight-common-c may still be submitting decode
     * units when the UI decides to leave; this lets the UI stop the presentation side promptly
     * while the connection unwinds at its own pace.
     */
    // !!! May be called even if setup()/start() fails !!!
    public void prepareForStop() {
        // Let the decoding code know to ignore codec exceptions now
        stopping = true;

        // Halt the rendering thread
        if (rendererThread != null) {
            rendererThread.interrupt();
        }

        // Stop any active codec recovery operations
        synchronized (codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
            codecRecoveryMonitor.notifyAll();
        }

        // Close the ADPF hint session before the threads it references go away. This happens
        // here rather than on the Choreographer looper because that looper only exists in
        // BALANCED pacing; in every other mode the session belongs to the renderer thread.
        // Both report sites null-check and 'stopping' is already set above, so they will not
        // touch the session after this point.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && perfHintSession != null) {
            perfHintSession.close();
            perfHintSession = null;
        }

        // Post a quit message to the Choreographer looper (if we have one)
        if (choreographerHandler != null) {
            choreographerHandler.post(new Runnable() {
                @Override
                public void run() {
                    // Don't allow any further messages to be queued
                    choreographerHandlerThread.quit();

                    // Deregister the frame callback (if registered)
                    Choreographer.getInstance().removeFrameCallback(MediaCodecDecoderRenderer.this);
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Joins the presentation threads so that nothing is inside the codec by the time
     * {@link #cleanup()} releases it.
     */
    @Override
    public void stop() {
        // May be called already, but we'll call it now to be safe
        prepareForStop();

        // Wait for the Choreographer looper to shut down (if we have one)
        if (choreographerHandlerThread != null) {
            try {
                choreographerHandlerThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }
        }

        // Wait for the renderer thread to shut down
        try {
            rendererThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();

            // InterruptedException clears the thread's interrupt status. Since we can't
            // handle that here, we will re-interrupt the thread to set the interrupt
            // status back to true.
            Thread.currentThread().interrupt();
        }
    }

    /** {@inheritDoc} Releases the codec. Only safe once {@link #stop()} has joined the threads. */
    @Override
    public void cleanup() {
        videoDecoder.release();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The mastering metadata is part of the codec configuration, so a change means restarting
     * the codec. The early returns matter: HDR mode is re-announced routinely, and restarting on
     * every announcement would drop frames for no reason.
     *
     * @param hdrMetadata CTA-861.3 mastering display metadata, or null when HDR is off
     */
    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        // HDR metadata is only supported in Android 7.0 and later, so don't bother
        // restarting the codec on anything earlier than that.
        if (currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
            currentHdrMetadata = null;
        }
        else if (enabled && hdrMetadata != null && !Arrays.equals(currentHdrMetadata, hdrMetadata)) {
            currentHdrMetadata = hdrMetadata;
        }
        else {
            // Nothing to do
            return;
        }

        // If we reach this point, we need to restart the MediaCodec instance to
        // pick up the HDR metadata change. This will happen on the next input
        // or output buffer.

        // HACK: Reset codec recovery attempt counter, since this is an expected "recovery"
        codecRecoveryAttempts = 0;

        // Promote None/Flush to Restart and leave Reset alone
        if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
            codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART);
        }
    }

    /**
     * Submits the filled input buffer to the decoder.
     *
     * @param timestampUs presentation timestamp, also used to measure decoder latency
     * @param codecFlags  {@code MediaCodec.BUFFER_FLAG_*} for this buffer
     * @return true if the buffer was accepted. False means the caller must request an IDR frame,
     *         because whatever was queued before it can no longer be decoded.
     */
    private boolean queueNextInputBuffer(long timestampUs, int codecFlags) {
        boolean codecRecovered;

        try {
            if (BuildConfig.DEBUG && perfMetricsEnabled) {
                recordDecodeStart(timestampUs);
            }

            videoDecoder.queueInputBuffer(nextInputBufferIndex,
                    0, nextInputBuffer.position(),
                    timestampUs, codecFlags);

            // We need a new buffer now
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        } catch (IllegalStateException e) {
            if (handleDecoderException(e)) {
                // We encountered a transient error. In this case, just hold onto the buffer
                // (to avoid leaking it), clear it, and keep it for the next frame. We'll return
                // false to trigger an IDR frame to recover.
                nextInputBuffer.clear();
            }
            else {
                // We encountered a non-transient error. In this case, we will simply leak the
                // buffer because we cannot be sure we will ever succeed in queuing it.
                nextInputBufferIndex = -1;
                nextInputBuffer = null;
            }
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        // Fetch a new input buffer now while we have some time between frames
        // to have it ready immediately when the next frame arrives.
        //
        // We must propagate the return value here in order to properly handle
        // codec recovery happening in fetchNextInputBuffer(). If we don't, we'll
        // never get an IDR frame to complete the recovery process.
        return fetchNextInputBuffer();
    }

    /**
     * Rewrites the SPS constraint flags to advertise Constrained High Profile where that helps.
     *
     * <p>Telling the decoder there will be no B-frames lets it stop buffering for reordering,
     * which is worth a frame or two of latency — but only on decoders confirmed to handle it, so
     * the flags are explicitly cleared everywhere else rather than left at their defaults.
     */
    private void doProfileSpecificSpsPatching(SeqParameterSet sps) {
        // Some devices benefit from setting constraint flags 4 & 5 to make this Constrained
        // High Profile which allows the decoder to assume there will be no B-frames and
        // reduce delay and buffering accordingly. Some devices (Marvell, Exynos 4) don't
        // like it so we only set them on devices that are confirmed to benefit from it.
        if (sps.profileIdc == 100 && constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile");
            sps.constraintSet4Flag = true;
            sps.constraintSet5Flag = true;
        }
        else {
            // Force the constraints unset otherwise (some may be set by default)
            sps.constraintSet4Flag = false;
            sps.constraintSet5Flag = false;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The hot path: called from moonlight-common-c's decode thread for every decode unit, and
     * responsible for stats, parameter-set handling, the per-device bitstream fixups, and getting
     * the data into the codec.
     *
     * <p>Parameter sets get special treatment. They are cached so they can be replayed after a
     * codec restart, and on decoders with known quirks the SPS is parsed, patched and re-emitted
     * — bitstream restrictions that some decoders need, and the baseline-profile hack for
     * decoders that reject High profile at configuration time.
     *
     * @return {@code MoonBridge.DR_OK}, or {@code DR_NEED_IDR} to ask the host for a fresh IDR
     *         frame when the decoder has lost its state
     */
    @SuppressWarnings("deprecation")
    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs) {
        if (stopping) {
            // Don't bother if we're stopping
            return MoonBridge.DR_OK;
        }

        updateFrameTracking(frameNumber, frameType);

        boolean csdSubmittedForThisFrame = false;

        // IDR frames require special handling for CSD buffer submission
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            // H264 SPS
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS && (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                numSpsIn++;

                ByteBuffer spsBuf = ByteBuffer.wrap(decodeUnitData);
                int startSeqLen = decodeUnitData[2] == 0x01 ? 3 : 4;

                // Skip to the start of the NALU data
                spsBuf.position(startSeqLen + 1);

                // The H264Utils.readSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                SeqParameterSet sps = H264Utils.readSPS(spsBuf);

                // Some decoders rely on H264 level to decide how many buffers are needed
                // Since we only need one frame buffered, we'll set the level as low as we can
                // for known resolution combinations. Reference frame invalidation may need
                // these, so leave them be for those decoders.
                if (!refFrameInvalidationActive) {
                    if (initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60) {
                        // Max 5 buffered frames at 720x480x60
                        LimeLog.info("Patching level_idc to 31");
                        sps.levelIdc = 31;
                    }
                    else if (initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60) {
                        // Max 5 buffered frames at 1280x720x60
                        LimeLog.info("Patching level_idc to 32");
                        sps.levelIdc = 32;
                    }
                    else if (initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60) {
                        // Max 4 buffered frames at 1920x1080x64
                        LimeLog.info("Patching level_idc to 42");
                        sps.levelIdc = 42;
                    }
                    else {
                        // Leave the profile alone (currently 5.0)
                    }
                }

                // TI OMAP4 requires a reference frame count of 1 to decode successfully. Exynos 4
                // also requires this fixup.
                //
                // I'm doing this fixup for all devices because I haven't seen any devices that
                // this causes issues for. At worst, it seems to do nothing and at best it fixes
                // issues with video lag, hangs, and crashes.
                //
                // It does break reference frame invalidation, so we will not do that for decoders
                // where we've enabled reference frame invalidation.
                if (!refFrameInvalidationActive) {
                    LimeLog.info("Patching num_ref_frames in SPS");
                    sps.numRefFrames = 1;
                }

                // Some older devices used to choke on a bitstream restrictions, so we won't provide them
                // unless explicitly whitelisted. For newer devices, leave the bitstream restrictions present.
                // The SPS that comes in the current H264 bytestream doesn't set bitstream_restriction_flag
                // or max_dec_frame_buffering which increases decoding latency on Tegra.

                // If the encoder didn't include VUI parameters in the SPS, add them now
                if (sps.vuiParams == null) {
                    LimeLog.info("Adding VUI parameters");
                    sps.vuiParams = new VUIParameters();
                }

                // GFE 2.5.11 started sending bitstream restrictions
                if (sps.vuiParams.bitstreamRestriction == null) {
                    LimeLog.info("Adding bitstream restrictions");
                    sps.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
                    sps.vuiParams.bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true;
                    sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                    sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                    sps.vuiParams.bitstreamRestriction.log2MaxMvLengthHorizontal = 16;
                    sps.vuiParams.bitstreamRestriction.log2MaxMvLengthVertical = 16;
                    sps.vuiParams.bitstreamRestriction.numReorderFrames = 0;
                }
                else {
                    LimeLog.info("Patching bitstream restrictions");
                }

                // Some devices throw errors if maxDecFrameBuffering < numRefFrames
                sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames;

                // log2_max_mv_length_horizontal and log2_max_mv_length_vertical are set to more
                // conservative values by GFE 2.5.11. We'll let those values stand.

                // If we need to hack this SPS to say we're baseline, do so now
                if (needsBaselineSpsHack) {
                    LimeLog.info("Hacking SPS to baseline");
                    sps.profileIdc = 66;
                    savedSps = sps;
                }

                // Patch the SPS constraint flags
                doProfileSpecificSpsPatching(sps);

                // The H264Utils.writeSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                ByteBuffer escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength);

                // Construct the patched SPS
                byte[] naluBuffer = new byte[startSeqLen + 1 + escapedNalu.limit()];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, startSeqLen + 1);
                escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit());

                // Batch this to submit together with other CSD per AOSP docs
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                vpsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            // Only the HEVC SPS hits this path (H.264 is handled above)
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                ppsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else {
                int csdResult = submitCsdBeforePicData();
                if (csdResult == CSD_FAILED) {
                    return MoonBridge.DR_NEED_IDR;
                }
                csdSubmittedForThisFrame = (csdResult == CSD_SUBMITTED);
            }
        }

        updateFrameCounters(frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs);

        if (!prepareInputBufferForData(decodeUnitLength, frameType, enqueueTimeUs, csdSubmittedForThisFrame)) {
            return MoonBridge.DR_NEED_IDR;
        }

        // Copy data from our buffer list into the input buffer
        nextInputBuffer.put(decodeUnitData, 0, decodeUnitLength);

        if (!queueNextInputBuffer(pendingTimestampUs, pendingCodecFlags)) {
            return MoonBridge.DR_NEED_IDR;
        }

        return MoonBridge.DR_OK;
    }

    /**
     * Per-frame bookkeeping done before anything touches the codec: loss detection, discarding
     * stale parameter sets at an IDR boundary, and rolling the measurement window over.
     *
     * <p>Shared by both submission paths so the statistics do not depend on which one a decode
     * unit arrived through.
     */
    private void updateFrameTracking(int frameNumber, int frameType) {
        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
        } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
            // We can receive the same "frame" multiple times if it's an IDR frame.
            // In that case, each frame start NALU is submitted independently.
            activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.frameLossEvents++;
        }

        // Reset CSD data for each IDR frame
        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            vpsBuffers.clear();
            spsBuffers.clear();
            ppsBuffers.clear();
        }

        lastFrameNumber = frameNumber;

        // Flip stats windows roughly every second
        if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            // Cache the overlay's visibility for the per-frame paths. They need it 60 times a
            // second and this is the only place already asking, so they read a field rather than
            // making an interface call. Visibility rather than the preference: the game menu's
            // toggle flips it mid-stream without touching prefConfig.
            perfMetricsEnabled = perfListener.isPerfOverlayVisible();

            if (perfMetricsEnabled) {
                // Snapshot here, format on the main thread. This runs on the decode thread, and
                // building the text meant a dozen resource lookups, three JNI stats calls, a
                // TrafficStats sample and a StringBuilder on the frame path once a second.
                //
                // The snapshot is required regardless: activeWindowVideoStats is cleared a few
                // lines below, so the formatter cannot be handed a live reference. The frame
                // rates are computed here too, since they derive from the time elapsed since the
                // window opened and would drift by however long the thread hop took.
                final VideoStats lastTwo = new VideoStats();
                lastTwo.add(lastWindowVideoStats);
                lastTwo.add(activeWindowVideoStats);

                final VideoStatsFps fps = lastTwo.getFps();
                final String decoder;

                if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                    decoder = avcDecoder.getName();
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                    decoder = hevcDecoder.getName();
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                    decoder = av1Decoder.getName();
                } else {
                    decoder = "(unknown)";
                }

                overlayHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        perfListener.onPerfUpdate(buildPerfOverlayText(lastTwo, fps, decoder));
                    }
                });
            }

            globalVideoStats.add(activeWindowVideoStats);
            lastWindowVideoStats.copy(activeWindowVideoStats);
            activeWindowVideoStats.clear();
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
        }

    }

    /**
     * Accumulates the per-decode-unit counters. Shared by both submission paths, and separate
     * from {@link #updateFrameTracking} because the parameter set handling runs between them.
     */
    private void updateFrameCounters(char frameHostProcessingLatency, long receiveTimeUs, long enqueueTimeUs) {
        if (frameHostProcessingLatency != 0) {
            if (activeWindowVideoStats.minHostProcessingLatency != 0) {
                activeWindowVideoStats.minHostProcessingLatency = (char) Math.min(activeWindowVideoStats.minHostProcessingLatency, frameHostProcessingLatency);
            } else {
                activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency;
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1;
        }
        activeWindowVideoStats.maxHostProcessingLatency = (char) Math.max(activeWindowVideoStats.maxHostProcessingLatency, frameHostProcessingLatency);
        activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency;

        activeWindowVideoStats.totalFramesReceived++;
        activeWindowVideoStats.totalFrames++;

        if (!FRAME_RENDER_TIME_ONLY) {
            // Count time from first packet received to enqueue time as receive time
            // We will count DU queue time as part of decoding, because it is directly
            // caused by a slow decoder.
            activeWindowVideoStats.totalTimeMs += (enqueueTimeUs - receiveTimeUs) / 1000;
        }
    }

    // Outcomes of submitCsdBeforePicData()
    private static final int CSD_NOT_NEEDED = 0;
    private static final int CSD_SUBMITTED = 1;
    private static final int CSD_FAILED = 2;

    /**
     * Submits the accumulated parameter sets ahead of the first picture data of an IDR frame.
     *
     * <p>Despite living among the parameter set handling, this runs on the <em>picture data</em>
     * decode unit, not on the parameter sets themselves: the SPS, PPS and VPS are cached as they
     * arrive, and this flushes them the moment the frame data that needs them shows up. Both
     * submission paths must call it, or a decoder never receives parameter sets at all.
     *
     * <p>AV1 is excluded because its sequence header travels in the frame data rather than as
     * separate parameter set NALUs.
     *
     * @return {@link #CSD_SUBMITTED} if parameter sets were queued, {@link #CSD_NOT_NEEDED} if
     *         they were not required, or {@link #CSD_FAILED} if the caller must request an IDR
     */
    private int submitCsdBeforePicData() {
        if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265)) == 0) {
            return CSD_NOT_NEEDED;
        }

        // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
        // submit the CSD blob in a separate input buffer for each IDR frame.
        if (submittedCsd && fusedIdrFrame) {
            return CSD_NOT_NEEDED;
        }

        if (!fetchNextInputBuffer()) {
            return CSD_FAILED;
        }

        // Submit all CSD when we receive the first non-CSD blob in an IDR frame
        for (byte[] vpsBuffer : vpsBuffers) {
            nextInputBuffer.put(vpsBuffer);
        }
        for (byte[] spsBuffer : spsBuffers) {
            nextInputBuffer.put(spsBuffer);
        }
        for (byte[] ppsBuffer : ppsBuffers) {
            nextInputBuffer.put(ppsBuffer);
        }

        if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
            return CSD_FAILED;
        }

        // Remember that we submitted CSD globally for this MediaCodec instance
        submittedCsd = true;

        if (needsBaselineSpsHack) {
            needsBaselineSpsHack = false;

            if (!replaySps()) {
                return CSD_FAILED;
            }

            LimeLog.info("SPS replay complete");
        }

        return CSD_SUBMITTED;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs the same preamble as {@link #submitDecodeUnit} and then stops, handing back the
     * codec's own input buffer so the caller can write the frame straight into it.
     *
     * <p>The parameter set parsing itself is skipped, since those decode units still take the
     * byte array path. {@link #submitCsdBeforePicData()} is <em>not</em> skipped: despite sitting
     * among that parsing, it runs on the picture data rather than on the parameter sets, flushing
     * the cached SPS, PPS and VPS ahead of the frame that needs them. Omitting it means a decoder
     * never receives parameter sets and decodes nothing.
     */
    @Override
    public ByteBuffer startPicData(int decodeUnitLength, int frameNumber, int frameType,
                                   char frameHostProcessingLatency, long receiveTimeUs, long enqueueTimeUs) {
        if (stopping) {
            return null;
        }

        updateFrameTracking(frameNumber, frameType);

        boolean csdSubmittedForThisFrame = false;
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            int csdResult = submitCsdBeforePicData();
            if (csdResult == CSD_FAILED) {
                return null;
            }
            csdSubmittedForThisFrame = (csdResult == CSD_SUBMITTED);
        }

        updateFrameCounters(frameHostProcessingLatency, receiveTimeUs, enqueueTimeUs);

        if (!prepareInputBufferForData(decodeUnitLength, frameType, enqueueTimeUs, csdSubmittedForThisFrame)) {
            return null;
        }

        return nextInputBuffer;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The position has to be advanced here. Native wrote through a raw pointer obtained from
     * {@code GetDirectBufferAddress}, which does not touch the ByteBuffer's own state, and
     * {@link #queueNextInputBuffer} takes the buffer's length from {@code position()}.
     */
    @Override
    public int submitPicData(int bytesWritten) {
        nextInputBuffer.position(nextInputBuffer.position() + bytesWritten);

        if (!queueNextInputBuffer(pendingTimestampUs, pendingCodecFlags)) {
            return MoonBridge.DR_NEED_IDR;
        }

        return MoonBridge.DR_OK;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Teardown is reported as success so that the host is not asked for an IDR frame on a
     * connection that is going away, matching what {@link #submitDecodeUnit} returns when
     * {@code stopping} is set.
     *
     * <p>Any input buffer fetched by {@link #startPicData} is retained rather than leaked:
     * {@link #fetchNextInputBuffer} short-circuits while {@code nextInputBuffer} is non-null, so
     * the next decode unit reuses it. It is reset here because a fused IDR frame will already
     * have written codec-specific data into it, and without this the retry would prepend a second
     * copy behind the first. Same reasoning as the transient-error path in
     * {@link #queueNextInputBuffer}.
     */
    @Override
    public int abortPicData() {
        if (nextInputBuffer != null) {
            nextInputBuffer.clear();
        }

        return stopping ? MoonBridge.DR_OK : MoonBridge.DR_NEED_IDR;
    }

    // Carried between prepareInputBufferForData() and the queueNextInputBuffer() that follows it.
    // Fields rather than parameters because the picture data path splits those two steps across a
    // return to native code. Safe: only the decode thread reaches either, and it already holds
    // nextInputBuffer across calls for the same reason.
    private long pendingTimestampUs;
    private int pendingCodecFlags;

    /**
     * Obtains an input buffer and makes it ready to receive {@code decodeUnitLength} bytes.
     *
     * <p>Everything between the per-frame statistics and the copy itself: fetching a buffer,
     * prepending codec-specific data for fused IDR frames, assigning the presentation timestamp,
     * and checking the data will fit. On success {@link #nextInputBuffer} is positioned where the
     * data belongs and {@link #pendingTimestampUs}/{@link #pendingCodecFlags} describe the pending
     * submission.
     *
     * @param csdSubmittedForThisFrame true if CSD was already queued separately for this frame
     * @return false if the caller must ask the host for a fresh IDR frame
     */
    private boolean prepareInputBufferForData(int decodeUnitLength, int frameType,
                                              long enqueueTimeUs, boolean csdSubmittedForThisFrame) {
        if (!fetchNextInputBuffer()) {
            return false;
        }

        int codecFlags = 0;

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            codecFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;

            // If we are using fused IDR frames, submit the CSD with each IDR frame
            if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                for (byte[] vpsBuffer : vpsBuffers) {
                    nextInputBuffer.put(vpsBuffer);
                }
                for (byte[] spsBuffer : spsBuffers) {
                    nextInputBuffer.put(spsBuffer);
                }
                for (byte[] ppsBuffer : ppsBuffers) {
                    nextInputBuffer.put(ppsBuffer);
                }
            }
        }

        long timestampUs = enqueueTimeUs;
        if (timestampUs <= lastTimestampUs) {
            // We can't submit multiple buffers with the same timestamp
            // so bump it up by one before queuing
            timestampUs = lastTimestampUs + 1;
        }
        lastTimestampUs = timestampUs;

        numFramesIn++;

        if (decodeUnitLength > nextInputBuffer.limit() - nextInputBuffer.position()) {
            IllegalArgumentException exception = new IllegalArgumentException(
                    "Decode unit length "+decodeUnitLength+" too large for input buffer "+nextInputBuffer.limit());
            if (!reportedCrash) {
                reportedCrash = true;
                crashListener.notifyCrash(exception);
            }
            throw new RendererException(this, exception);
        }

        pendingTimestampUs = timestampUs;
        pendingCodecFlags = codecFlags;
        return true;
    }

    /**
     * Formats one overlay update from a snapshot of the window that just closed.
     *
     * <p>Called on {@link #overlayHandler}, never on the decode thread. Everything expensive
     * lives here on purpose: the resource lookups, the three JNI stats calls, the
     * {@link TrafficStats} sample and the string building. The caller does only the snapshot,
     * because {@code activeWindowVideoStats} is cleared immediately afterwards.
     *
     * @param lastTwo the last two windows summed, already detached from the live counters
     * @param fps     rates derived at snapshot time, so the thread hop cannot skew them
     * @param decoder the decoder name for the format actually in use
     */
    private String buildPerfOverlayText(VideoStats lastTwo, VideoStatsFps fps, String decoder) {
        float decodeTimeMs = (float)lastTwo.decoderTimeMs / lastTwo.totalFramesReceived;
        long rttInfo = MoonBridge.getEstimatedRttInfo();
        StringBuilder sb = new StringBuilder();
        sb.append(context.getString(R.string.perf_overlay_streamdetails, initialWidth + "x" + initialHeight, fps.totalFps)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_netdrops,
                (float)lastTwo.framesLost / lastTwo.totalFrames * 100)).append('\n');
        sb.append(context.getString(R.string.perf_overlay_netlatency,
                (int)(rttInfo >> 32), (int)rttInfo)).append('\n');
        trafficStats.sample();
        sb.append(context.getString(R.string.perf_overlay_bandwidth,
                trafficStats.getRxKBps() / 1024.f, trafficStats.getTxKBps() / 1024.f)).append('\n');
        long[] videoRtpStats = MoonBridge.getRTPVideoStats();
        long[] audioRtpStats = MoonBridge.getRTPAudioStats();
        if (videoRtpStats != null && audioRtpStats != null) {
            sb.append(context.getString(R.string.perf_overlay_fec,
                    videoRtpStats[MoonBridge.RTP_STAT_FEC_RECOVERED],
                    audioRtpStats[MoonBridge.RTP_STAT_FEC_RECOVERED],
                    videoRtpStats[MoonBridge.RTP_STAT_FEC_FAILED],
                    audioRtpStats[MoonBridge.RTP_STAT_FEC_FAILED])).append('\n');

            // Only shown once something has actually failed. A healthy stream reads zero
            // forever, and a line that is always zero stops being read.
            long videoDecryptFailed = videoRtpStats[MoonBridge.RTP_STAT_DECRYPT_FAILED];
            long audioDecryptFailed = audioRtpStats[MoonBridge.RTP_STAT_DECRYPT_FAILED];
            if (videoDecryptFailed != 0 || audioDecryptFailed != 0) {
                sb.append(context.getString(R.string.perf_overlay_decryptfail,
                        videoDecryptFailed, audioDecryptFailed)).append('\n');
            }
        }
        if (lastTwo.framesWithHostProcessingLatency > 0) {
            sb.append(context.getString(R.string.perf_overlay_hostprocessinglatency,
                    (float)lastTwo.minHostProcessingLatency / 10,
                    (float)lastTwo.maxHostProcessingLatency / 10,
                    (float)lastTwo.totalHostProcessingLatency / 10 / lastTwo.framesWithHostProcessingLatency)).append('\n');
        }
        // Debug only, because the measurements behind them are. Release builds must not show these
        // rows at all rather than show them reading zero - that is exactly the failure the decode
        // time metric had for its whole existence, and a blank row invites the same misreading.
        if (BuildConfig.DEBUG) {
            sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs)).append('\n');
            sb.append(context.getString(R.string.perf_overlay_presentgaps,
                    presentationGapCount, (float)worstPresentationGapNanos / 1000000));
        }

        return sb.toString();
    }

    /**
     * Re-submits the saved SPS with High profile restored, completing the baseline SPS hack.
     *
     * <p>Some decoders reject a High profile SPS at configuration time but accept it once
     * running, so configuration is done with a doctored baseline SPS and the real one is replayed
     * here immediately afterwards.
     *
     * @return true if the patched SPS was queued
     */
    private boolean replaySps() {
        if (!fetchNextInputBuffer()) {
            return false;
        }

        // Write the Annex B header
        nextInputBuffer.put(new byte[]{0x00, 0x00, 0x00, 0x01, 0x67});

        // Switch the H264 profile back to high
        savedSps.profileIdc = 100;

        // Patch the SPS constraint flags
        doProfileSpecificSpsPatching(savedSps);

        // The H264Utils.writeSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        ByteBuffer escapedNalu = H264Utils.writeSPS(savedSps, 128);
        nextInputBuffer.put(escapedNalu);

        // No need for the SPS anymore
        savedSps = null;

        // Queue the new SPS
        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Advertises the latency features this decoder can handle: slices per frame, reference
     * frame invalidation (recovering from packet loss without a full IDR frame) and direct
     * submit. Note that this can be called before {@link #setup}, so it reports capabilities for
     * every codec rather than only the one in use — which is why the constructor resolves them.
     */
    @Override
    public int getCapabilities() {
        int capabilities = 0;

        // Request the optimal number of slices per frame for this decoder
        capabilities |= MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame);

        // Enable reference frame invalidation on supported hardware
        if (refFrameInvalidationAvc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC;
        }
        if (refFrameInvalidationHevc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC;
        }
        if (refFrameInvalidationAv1) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1;
        }

        // Enable direct submit on supported hardware
        if (directSubmit) {
            capabilities |= MoonBridge.CAPABILITY_DIRECT_SUBMIT;
        }

        return capabilities;
    }

    /**
     * @return mean milliseconds from a frame arriving to it being presented, over the whole
     *         session, or 0 if no frames have been received
     */
    public int getAverageEndToEndLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived);
    }

    /**
     * @return mean milliseconds spent inside the decoder per frame, over the whole session, or 0
     *         if no frames have been received
     */
    public int getAverageDecoderLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived);
    }

    /** Raised when the decoder stops accepting input buffers entirely; see {@link #fetchNextInputBuffer()}. */
    static class DecoderHungException extends RuntimeException {
        private int hangTimeMs;

        DecoderHungException(int hangTimeMs) {
            this.hangTimeMs = hangTimeMs;
        }

        public String toString() {
            String str = "";

            str += "Hang time: "+hangTimeMs+" ms"+ RendererException.DELIMITER;
            str += super.toString();

            return str;
        }
    }

    /**
     * Wraps a decoder failure together with a snapshot of how far the stream had progressed.
     *
     * <p>The message is built at construction time rather than lazily, and starts with a
     * classification of the failure stage (pre-SPS, pre-IDR, and so on) followed by decoder name,
     * format and counters. Crash reports on a device you don't have in front of you are nearly
     * useless without that context.
     */
    static class RendererException extends RuntimeException {
        private static final long serialVersionUID = 8985937536997012406L;
        protected static final String DELIMITER = BuildConfig.DEBUG ? "\n" : " | ";

        private String text;

        RendererException(MediaCodecDecoderRenderer renderer, Exception e) {
            this.text = generateText(renderer, e);
        }

        public String toString() {
            return text;
        }

        private String generateText(MediaCodecDecoderRenderer renderer, Exception originalException) {
            String str;

            if (renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0) {
                str = "PreSPSError";
            }
            else if (renderer.numSpsIn > 0 && renderer.numPpsIn == 0) {
                str = "PrePPSError";
            }
            else if (renderer.numPpsIn > 0 && renderer.numFramesIn == 0) {
                str = "PreIFrameError";
            }
            else if (renderer.numFramesIn > 0 && renderer.outputFormat == null) {
                str = "PreOutputConfigError";
            }
            else if (renderer.outputFormat != null && renderer.numFramesOut == 0) {
                str = "PreOutputError";
            }
            else if (renderer.numFramesOut <= renderer.refreshRate * 30) {
                str = "EarlyOutputError";
            }
            else {
                str = "ErrorWhileStreaming";
            }

            str += "Format: "+String.format("%x", renderer.videoFormat)+DELIMITER;
            str += "AVC Decoder: "+((renderer.avcDecoder != null) ? renderer.avcDecoder.getName():"(none)")+DELIMITER;
            str += "HEVC Decoder: "+((renderer.hevcDecoder != null) ? renderer.hevcDecoder.getName():"(none)")+DELIMITER;
            str += "AV1 Decoder: "+((renderer.av1Decoder != null) ? renderer.av1Decoder.getName():"(none)")+DELIMITER;
            if (renderer.avcDecoder != null) {
                Range<Integer> avcWidthRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();
                str += "AVC supported width range: "+avcWidthRange+DELIMITER;
                try {
                    Range<Double> avcFpsRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                    str += "AVC achievable FPS range: "+avcFpsRange+DELIMITER;
                } catch (IllegalArgumentException e) {
                    str += "AVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                }
            }
            if (renderer.hevcDecoder != null) {
                Range<Integer> hevcWidthRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();
                str += "HEVC supported width range: "+hevcWidthRange+DELIMITER;
                try {
                    Range<Double> hevcFpsRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                    str += "HEVC achievable FPS range: " + hevcFpsRange + DELIMITER;
                } catch (IllegalArgumentException e) {
                    str += "HEVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                }
            }
            if (renderer.av1Decoder != null) {
                Range<Integer> av1WidthRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getSupportedWidths();
                str += "AV1 supported width range: "+av1WidthRange+DELIMITER;
                try {
                    Range<Double> av1FpsRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                    str += "AV1 achievable FPS range: " + av1FpsRange + DELIMITER;
                } catch (IllegalArgumentException e) {
                    str += "AV1 achievable FPS range: UNSUPPORTED!"+DELIMITER;
                }
            }
            str += "Configured format: "+renderer.configuredFormat+DELIMITER;
            str += "Input format: "+renderer.inputFormat+DELIMITER;
            str += "Output format: "+renderer.outputFormat+DELIMITER;
            str += "Adaptive playback: "+renderer.adaptivePlayback+DELIMITER;
            str += "GL Renderer: "+renderer.glRenderer+DELIMITER;
            //str += "Build fingerprint: "+Build.FINGERPRINT+DELIMITER;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                str += "SOC: "+Build.SOC_MANUFACTURER+" - "+Build.SOC_MODEL+DELIMITER;
                str += "Performance class: "+Build.VERSION.MEDIA_PERFORMANCE_CLASS+DELIMITER;
                /*str += "Vendor params: ";
                List<String> params = renderer.videoDecoder.getSupportedVendorParameters();
                if (params.isEmpty()) {
                    str += "NONE";
                }
                else {
                    for (String param : params) {
                        str += param + " ";
                    }
                }
                str += DELIMITER;*/
            }
            str += "Consecutive crashes: "+renderer.consecutiveCrashCount+DELIMITER;
            str += "RFI active: "+renderer.refFrameInvalidationActive+DELIMITER;
            str += "Fused IDR frames: "+renderer.fusedIdrFrame+DELIMITER;
            str += "Video dimensions: "+renderer.initialWidth+"x"+renderer.initialHeight+DELIMITER;
            str += "FPS target: "+renderer.refreshRate+DELIMITER;
            str += "Bitrate: "+renderer.prefs.bitrate+" Kbps"+DELIMITER;
            str += "CSD stats: "+renderer.numVpsIn+", "+renderer.numSpsIn+", "+renderer.numPpsIn+DELIMITER;
            str += "Frames in-out: "+renderer.numFramesIn+", "+renderer.numFramesOut+DELIMITER;
            str += "Total frames received: "+renderer.globalVideoStats.totalFramesReceived+DELIMITER;
            str += "Total frames rendered: "+renderer.globalVideoStats.totalFramesRendered+DELIMITER;
            str += "Frame losses: "+renderer.globalVideoStats.framesLost+" in "+renderer.globalVideoStats.frameLossEvents+" loss events"+DELIMITER;
            long[] videoRtp = MoonBridge.getRTPVideoStats();
            long[] audioRtp = MoonBridge.getRTPAudioStats();
            if (videoRtp != null) {
                str += "Video RTP packets/FEC/recovered/failed/OOS/invalid/decrypt-failed: "+
                        videoRtp[MoonBridge.RTP_STAT_PACKETS]+", "+videoRtp[MoonBridge.RTP_STAT_FEC]+", "+
                        videoRtp[MoonBridge.RTP_STAT_FEC_RECOVERED]+", "+videoRtp[MoonBridge.RTP_STAT_FEC_FAILED]+", "+
                        videoRtp[MoonBridge.RTP_STAT_OOS]+", "+videoRtp[MoonBridge.RTP_STAT_INVALID]+", "+
                        videoRtp[MoonBridge.RTP_STAT_DECRYPT_FAILED]+DELIMITER;
            }
            if (audioRtp != null) {
                str += "Audio RTP packets/FEC/recovered/failed/OOS/invalid/decrypt-failed: "+
                        audioRtp[MoonBridge.RTP_STAT_PACKETS]+", "+audioRtp[MoonBridge.RTP_STAT_FEC]+", "+
                        audioRtp[MoonBridge.RTP_STAT_FEC_RECOVERED]+", "+audioRtp[MoonBridge.RTP_STAT_FEC_FAILED]+", "+
                        audioRtp[MoonBridge.RTP_STAT_OOS]+", "+audioRtp[MoonBridge.RTP_STAT_INVALID]+", "+
                        audioRtp[MoonBridge.RTP_STAT_DECRYPT_FAILED]+DELIMITER;
            }
            str += "Average end-to-end client latency: "+renderer.getAverageEndToEndLatency()+"ms"+DELIMITER;
            str += "Average hardware decoder latency: "+renderer.getAverageDecoderLatency()+"ms"+DELIMITER;
            str += "Frame pacing mode: "+renderer.prefs.framePacing+DELIMITER;

            if (originalException instanceof CodecException) {
                CodecException ce = (CodecException) originalException;

                str += "Diagnostic Info: "+ce.getDiagnosticInfo()+DELIMITER;
                str += "Recoverable: "+ce.isRecoverable()+DELIMITER;
                str += "Transient: "+ce.isTransient()+DELIMITER;

                str += "Codec Error Code: "+ce.getErrorCode()+DELIMITER;
            }

            str += originalException.toString();

            return str;
        }
    }
}
