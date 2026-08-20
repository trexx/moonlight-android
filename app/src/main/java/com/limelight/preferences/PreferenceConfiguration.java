package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;

import java.util.Locale;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Every user-facing setting, read once into a plain object.
 *
 * <p>{@link #readPreferences} is the only place preference keys are interpreted: everything else
 * reads fields off this object. That matters because several settings are stored differently from
 * how they are used — legacy boolean keys are migrated to enums, resolutions are parsed from
 * strings, and defaults are computed from the device rather than fixed.
 *
 * <p>The keys themselves live in {@code res/xml/preferences.xml}, which is where the ranges and
 * option lists are defined.
 */
public class PreferenceConfiguration {
    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    };

    public enum MouseMode {
        ABSOLUTE,          // touch maps directly to a screen position
        ABSOLUTE_SWAPPED,  // as above, with primary/secondary buttons swapped
        RELATIVE,          // classic trackpad
        TRACKPAD,          // trackpad with momentum scrolling and multi-finger gestures
    }

    public enum ScaleMode {
        FIT,      // letterbox, preserve aspect ratio
        FILL,     // overscan and crop, preserve aspect ratio
        STRETCH,  // distort to fill
    }

    public enum AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    private static final String LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps";
    private static final String LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround";

    static final String RESOLUTION_PREF_STRING = "list_resolution";
    static final String FPS_PREF_STRING = "list_fps";
    static final String BITRATE_PREF_STRING = "seekbar_bitrate_kbps";
    private static final String BITRATE_PREF_OLD_STRING = "seekbar_bitrate";
    private static final String STRETCH_PREF_STRING = "checkbox_stretch_video";
    private static final String SCALE_MODE_PREF_STRING = "list_video_scale_mode";
    private static final String SOPS_PREF_STRING = "checkbox_enable_sops";
    private static final String DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings";
    private static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String ENFORCE_DISPLAY_MODE_PREF_STRING = "checkbox_enforce_display_mode";
    private static final String RESUME_WITHOUT_CONFIRM_PREF_STRING = "checkbox_resume_without_confirm";
    private static final String SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode";
    private static final String MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller";
    static final String AUDIO_CONFIG_PREF_STRING = "list_audio_config";
    private static final String USB_DRIVER_PREF_SRING = "checkbox_usb_driver";
    private static final String VIDEO_FORMAT_PREF_STRING = "video_format";
    static final String ENCRYPTION_PREF_STRING = "list_encryption";
    private static final String LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop";
    private static final String ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr";
    private static final String ENABLE_INTRA_REFRESH_PREF_STRING = "checkbox_enable_intra_refresh";
    private static final String ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay";
    private static final String BIND_ALL_USB_STRING = "checkbox_usb_bind_all";
    private static final String WIRED_PAD_AUDIO_STRING = "checkbox_wired_pad_audio";
    private static final String MOUSE_EMULATION_STRING = "checkbox_mouse_emulation";
    private static final String ANALOG_SCROLLING_PREF_STRING = "analog_scrolling";
    private static final String MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons";
    static final String UNLOCK_FPS_STRING = "checkbox_unlock_fps";
    private static final String FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons";
    private static final String TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad";
    private static final String MOUSE_MODE_PREF_STRING = "list_mouse_mode";
    private static final String LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast";
    private static final String FRAME_PACING_PREF_STRING = "frame_pacing";
    private static final String ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode";
    private static final String ENABLE_AAUDIO_PREF_STRING = "checkbox_enable_aaudio";
    private static final String CONTINUOUS_AUDIO_PREF_STRING = "checkbox_continuous_audio";
    private static final String REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate";
    private static final String FULL_RANGE_PREF_STRING = "checkbox_full_range";
    private static final String GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse";
    private static final String GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors";
    private static final String SEND_REAL_CLIENT_ID_PREF_STRING = "checkbox_send_real_client_id";

    static final String DEFAULT_RESOLUTION = "1280x720";
    static final String DEFAULT_FPS = "60";
    private static final boolean DEFAULT_STRETCH = false;
    private static final boolean DEFAULT_SOPS = true;
    private static final boolean DEFAULT_DISABLE_TOASTS = false;
    private static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 7;
    private static final boolean DEFAULT_ENFORCE_DISPLAY_MODE = false;
    private static final boolean DEFAULT_RESUME_WITHOUT_CONFIRM = false;
    private static final boolean DEFAULT_MULTI_CONTROLLER = true;
    private static final boolean DEFAULT_USB_DRIVER = true;
    private static final String DEFAULT_VIDEO_FORMAT = "auto";
    static final String DEFAULT_ENCRYPTION = "audio";

    private static final boolean DEFAULT_ENABLE_HDR = false;
    private static final boolean DEFAULT_ENABLE_INTRA_REFRESH = false;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY = false;
    private static final boolean DEFAULT_BIND_ALL_USB = false;
    // Off: claiming a cabled pad replaces a working kernel driver, which is the user's call
    private static final boolean DEFAULT_WIRED_PAD_AUDIO = false;
    private static final boolean DEFAULT_MOUSE_EMULATION = true;
    private static final String DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right";
    private static final boolean DEFAULT_MOUSE_NAV_BUTTONS = false;
    private static final boolean DEFAULT_UNLOCK_FPS = false;
    private static final boolean DEFAULT_FLIP_FACE_BUTTONS = false;
    private static final boolean DEFAULT_TOUCHSCREEN_TRACKPAD = true;
    private static final String DEFAULT_AUDIO_CONFIG = "2"; // Stereo
    private static final boolean DEFAULT_LATENCY_TOAST = false;
    private static final String DEFAULT_FRAME_PACING = "latency";
    private static final boolean DEFAULT_ABSOLUTE_MOUSE_MODE = false;
    private static final boolean DEFAULT_ENABLE_AAUDIO = false;
    private static final boolean DEFAULT_CONTINUOUS_AUDIO = false;
    private static final boolean DEFAULT_REDUCE_REFRESH_RATE = false;
    private static final boolean DEFAULT_FULL_RANGE = false;
    private static final boolean DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false;
    private static final boolean DEFAULT_GAMEPAD_MOTION_SENSORS = true;

    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;

    public static final String RES_360P = "640x360";
    public static final String RES_480P = "854x480";
    public static final String RES_720P = "1280x720";
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";
    public static final String RES_4K = "3840x2160";
    public static final String RES_NATIVE = "Native";

    public int width, height, fps;
    public int bitrate;
    public FormatOption videoFormat;
    public int encryptionFlags;
    public int deadzonePercentage;
    public boolean enforceDisplayMode;
    public boolean resumeWithoutConfirm;
    public boolean stretchVideo, enableSops, playHostAudio, disableWarnings;
    public ScaleMode scaleMode;
    public boolean smallIconMode, multiController, usbDriver, flipFaceButtons;
    public boolean enableHdr;
    public boolean enableIntraRefresh;
    public boolean enablePerfOverlay;
    public boolean enableLatencyToast;
    public boolean bindAllUsb;
    /** Whether to drive cabled Xbox pads ourselves so their headphone jack can take audio. */
    public boolean wiredPadAudio;
    public boolean mouseEmulation;
    public AnalogStickForScrolling analogStickForScrolling;
    public boolean mouseNavButtons;
    public boolean unlockFps;
    public boolean touchscreenTrackpad;
    public MouseMode mouseMode;
    public MoonBridge.AudioConfiguration audioConfiguration;
    public int framePacing;
    public boolean absoluteMouseMode;
    public boolean enableAAudio;
    public boolean continuousAudio;
    public boolean reduceRefreshRate;
    public boolean fullRange;
    public boolean gamepadMotionSensors;
    public boolean gamepadTouchpadAsMouse;

    /** @return true if this resolution matches the device's own panel, rather than a standard mode */
    public static boolean isNativeResolution(int width, int height) {
        // It's not a native resolution if it matches an existing resolution option
        if (width == 640 && height == 360) {
            return false;
        }
        else if (width == 854 && height == 480) {
            return false;
        }
        else if (width == 1280 && height == 720) {
            return false;
        }
        else if (width == 1920 && height == 1080) {
            return false;
        }
        else if (width == 2560 && height == 1440) {
            return false;
        }
        else if (width == 3840 && height == 2160) {
            return false;
        }

        return true;
    }

    // If we have a screen that has semi-square dimensions, we may want to change our behavior
    // to allow any orientation and vertical+horizontal resolutions.
    /**
     * @return true if the display is close enough to square that the usual 16:9 assumptions break,
     *         which affects the resolution options offered
     */
    public static boolean isSquarishScreen(int width, int height) {
        float longDim = Math.max(width, height);
        float shortDim = Math.min(width, height);

        // We just put the arbitrary cutoff for a square-ish screen at 1.3
        return longDim / shortDim < 1.3f;
    }

    private static String convertFromLegacyResolutionString(String resString) {
        // Locale.ROOT, not the default: the old form used equalsIgnoreCase, which is
        // locale-independent, and a Turkish locale lowercases "4K" to "4ı" rather than "4k".
        return switch (resString.toLowerCase(Locale.ROOT)) {
            case "360p" -> RES_360P;
            case "480p" -> RES_480P;
            case "1080p" -> RES_1080P;
            case "1440p" -> RES_1440P;
            case "4k" -> RES_4K;
            // "720p", plus anything unrecognised — this only runs against values the app itself
            // wrote, so an unknown one means a hand-edited preference file.
            default -> RES_720P;
        };
    }

    private static int getWidthFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[0]);
    }

    private static int getHeightFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[1]);
    }

    private static String getResolutionString(int width, int height) {
        return switch (height) {
            case 360 -> RES_360P;
            case 480 -> RES_480P;
            case 1080 -> RES_1080P;
            case 1440 -> RES_1440P;
            case 2160 -> RES_4K;
            // 720, and anything else. The old form wrote this as a `default:` label sitting above
            // `case 720:` and sharing its body, which reads like a bug and is not one.
            default -> RES_720P;
        };
    }

    /** @return the default bitrate in Kbps for a resolution and frame rate, before user override */
    public static int getDefaultBitrate(String resString, String fpsString) {
        int width = getWidthFromResolutionString(resString);
        int height = getHeightFromResolutionString(resString);
        int fps = Integer.parseInt(fpsString);

        // This logic is shamelessly stolen from Moonlight Qt:
        // https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp

        // Don't scale bitrate linearly beyond 60 FPS. It's definitely not a linear
        // bitrate increase for frame rate once we get to values that high.
        double frameRateFactor = (fps <= 60 ? fps : (Math.sqrt(fps / 60.f) * 60.f)) / 30.f;

        // TODO: Collect some empirical data to see if these defaults make sense.
        // We're just using the values that the Shield used, as we have for years.
        int[] pixelVals = {
            640 * 360,
            854 * 480,
            1280 * 720,
            1920 * 1080,
            2560 * 1440,
            3840 * 2160,
            -1,
        };
        int[] factorVals = {
            1,
            2,
            5,
            10,
            20,
            40,
            -1
        };

        // Calculate the resolution factor by linear interpolation of the resolution table
        float resolutionFactor;
        int pixels = width * height;
        for (int i = 0; ; i++) {
            if (pixels == pixelVals[i]) {
                // We can bail immediately for exact matches
                resolutionFactor = factorVals[i];
                break;
            }
            else if (pixels < pixelVals[i]) {
                if (i == 0) {
                    // Never go below the lowest resolution entry
                    resolutionFactor = factorVals[i];
                }
                else {
                    // Interpolate between the entry greater than the chosen resolution (i) and the entry less than the chosen resolution (i-1)
                    resolutionFactor = ((float)(pixels - pixelVals[i-1]) / (pixelVals[i] - pixelVals[i-1])) * (factorVals[i] - factorVals[i-1]) + factorVals[i-1];
                }
                break;
            }
            else if (pixelVals[i] == -1) {
                // Never go above the highest resolution entry
                resolutionFactor = factorVals[i-1];
                break;
            }
        }

        return (int)Math.round(resolutionFactor * frameRateFactor) * 1000;
    }

    /** @return true if the grid should default to small icons, based on screen size */
    public static boolean getDefaultSmallMode(Context context) {
        PackageManager manager = context.getPackageManager();
        if (manager != null) {
            // TVs shouldn't use small mode by default
            if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                return false;
            }

            // API 21 uses LEANBACK instead of TELEVISION
            if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                return false;
            }
        }

        // Use small mode on anything smaller than a 7" tablet
        return context.getResources().getConfiguration().smallestScreenWidthDp < 500;
    }

    /**
     * @return true if hosts should be told this install's own client ID rather than the value
     *         every Moonlight client shares
     *
     * <p>Read on its own rather than through {@link #readPreferences}, because
     * {@code IdentityManager} needs it per HTTP request and has no use for the rest of the
     * configuration. Interpreting the key here keeps this class the only place that does.
     */
    public static boolean sendRealClientId(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(SEND_REAL_CLIENT_ID_PREF_STRING, false);
    }

    /** @return the default bitrate for the settings currently stored for this device */
    public static int getDefaultBitrate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION),
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS));
    }

    /**
     * Maps the stored preference value to an {@code ENCFLG_*} mask.
     *
     * <p>Split out and static so it can be unit tested: {@link PreferenceConfiguration} itself
     * needs a {@link Context} and cannot load on a JVM.
     *
     * @param value the stored string, or null if the preference has never been written
     * @return the mask to request, defaulting to audio-only for anything unrecognised
     */
    static int getEncryptionFlagsValue(String value) {
        if (value == null) {
            value = DEFAULT_ENCRYPTION;
        }

        return switch (value) {
            case "none" -> StreamConfiguration.ENCFLG_NONE;
            case "all" -> StreamConfiguration.ENCFLG_ALL;
            // "audio", plus anything unrecognised: match the upstream baseline rather than
            // silently dropping encryption because a value was mistyped.
            default -> StreamConfiguration.ENCFLG_AUDIO;
        };
    }

    private static FormatOption getVideoFormatValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        return switch (prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT)) {
            case "forceav1" -> FormatOption.FORCE_AV1;
            case "forceh265" -> FormatOption.FORCE_HEVC;
            case "neverh265" -> FormatOption.FORCE_H264;
            // "auto", plus anything unrecognised
            default -> FormatOption.AUTO;
        };
    }

    private static int getFramePacingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Migrate legacy never drop frames option to the new location
        if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
            boolean legacyNeverDropFrames = prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false);
            prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, legacyNeverDropFrames ? "balanced" : "latency")
                    .apply();
        }

        return switch (prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING)) {
            case "balanced" -> FRAME_PACING_BALANCED;
            case "cap-fps" -> FRAME_PACING_CAP_FPS;
            case "smoothness" -> FRAME_PACING_MAX_SMOOTHNESS;
            // "latency", plus anything unrecognised: this fork's default, and the safe one to
            // fall back to on a value nobody recognises.
            default -> FRAME_PACING_MIN_LATENCY;
        };
    }

    private static AnalogStickForScrolling getAnalogStickForScrollingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        return switch (prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING)) {
            case "right" -> AnalogStickForScrolling.RIGHT;
            case "left" -> AnalogStickForScrolling.LEFT;
            default -> AnalogStickForScrolling.NONE;
        };
    }

    /**
     * Restores the streaming settings to their defaults, leaving unrelated preferences alone.
     * Offered as a recovery path when a setting has made streaming fail.
     */
    public static void resetStreamingSettings(Context context) {
        // We consider resolution, FPS, bitrate, HDR, and video format as "streaming settings" here
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply();
    }

    /**
     * Reads and normalises every setting, migrating legacy keys and computing device-dependent
     * defaults on the way.
     *
     * @return a snapshot; later preference changes are not reflected in it
     */
    public static PreferenceConfiguration readPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PreferenceConfiguration config = new PreferenceConfiguration();

        // Migrate legacy preferences to the new locations
        if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
            if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply();
            }
        }

        String str = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null);
        if (str != null) {
            if (str.equals("360p30")) {
                config.width = 640;
                config.height = 360;
                config.fps = 30;
            }
            else if (str.equals("360p60")) {
                config.width = 640;
                config.height = 360;
                config.fps = 60;
            }
            else if (str.equals("720p30")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 30;
            }
            else if (str.equals("720p60")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }
            else if (str.equals("1080p30")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 30;
            }
            else if (str.equals("1080p60")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 60;
            }
            else if (str.equals("4K30")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 30;
            }
            else if (str.equals("4K60")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 60;
            }
            else {
                // Should never get here
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }

            prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(RESOLUTION_PREF_STRING, getResolutionString(config.width, config.height))
                    .putString(FPS_PREF_STRING, ""+config.fps)
                    .apply();
        }
        else {
            // Use the new preference location
            String resStr = prefs.getString(RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);

            // Convert legacy resolution strings to the new style
            if (!resStr.contains("x")) {
                resStr = PreferenceConfiguration.convertFromLegacyResolutionString(resStr);
                prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply();
            }

            config.width = PreferenceConfiguration.getWidthFromResolutionString(resStr);
            config.height = PreferenceConfiguration.getHeightFromResolutionString(resStr);
            config.fps = Integer.parseInt(prefs.getString(FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS));
        }

        if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
            // We need to write small icon mode's default to disk for the settings page to display
            // the current state of the option properly
            prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply();
        }

        // This must happen after the preferences migration to ensure the preferences are populated
        config.bitrate = prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000);
        if (config.bitrate == 0) {
            config.bitrate = getDefaultBitrate(context);
        }

        String audioConfig = prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG);
        if (audioConfig.equals("71")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_71_SURROUND;
        }
        else if (audioConfig.equals("51")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND;
        }
        else /* if (audioConfig.equals("2")) */ {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        }

        config.videoFormat = getVideoFormatValue(context);
        config.encryptionFlags = getEncryptionFlagsValue(
                prefs.getString(ENCRYPTION_PREF_STRING, DEFAULT_ENCRYPTION));
        config.framePacing = getFramePacingValue(context);

        config.analogStickForScrolling = getAnalogStickForScrollingValue(context);

        config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE);
        config.enforceDisplayMode = prefs.getBoolean(ENFORCE_DISPLAY_MODE_PREF_STRING, DEFAULT_ENFORCE_DISPLAY_MODE);
        config.resumeWithoutConfirm = prefs.getBoolean(RESUME_WITHOUT_CONFIRM_PREF_STRING, DEFAULT_RESUME_WITHOUT_CONFIRM);



        // Checkbox preferences
        config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS);
        config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS);
        // Migrate the old boolean stretch preference into the three-way scale mode the
        // first time we run, so existing users keep the behaviour they had.
        String scaleModeValue = prefs.getString(SCALE_MODE_PREF_STRING, null);
        if (scaleModeValue == null) {
            scaleModeValue = prefs.getBoolean(STRETCH_PREF_STRING, DEFAULT_STRETCH) ? "stretch" : "fit";
            prefs.edit().putString(SCALE_MODE_PREF_STRING, scaleModeValue).apply();
        }
        config.scaleMode = switch (scaleModeValue) {
            case "stretch" -> ScaleMode.STRETCH;
            case "fill" -> ScaleMode.FILL;
            default -> ScaleMode.FIT;
        };
        config.stretchVideo = config.scaleMode == ScaleMode.STRETCH;
        config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO);
        config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context));
        config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER);
        config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER);
        config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR);
        config.enableIntraRefresh = prefs.getBoolean(ENABLE_INTRA_REFRESH_PREF_STRING, DEFAULT_ENABLE_INTRA_REFRESH);
        config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY);
        config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB);
        config.wiredPadAudio = prefs.getBoolean(WIRED_PAD_AUDIO_STRING, DEFAULT_WIRED_PAD_AUDIO);
        config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION);
        config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS);
        config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS);
        config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS);
        // Migrate the old boolean trackpad preference into the mouse mode list on first run
        String mouseModeValue = prefs.getString(MOUSE_MODE_PREF_STRING, null);
        if (mouseModeValue == null) {
            mouseModeValue = prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD) ?
                    "relative" : "absolute";
            prefs.edit().putString(MOUSE_MODE_PREF_STRING, mouseModeValue).apply();
        }
        config.mouseMode = switch (mouseModeValue) {
            case "absolute_swapped" -> MouseMode.ABSOLUTE_SWAPPED;
            case "relative" -> MouseMode.RELATIVE;
            case "trackpad" -> MouseMode.TRACKPAD;
            default -> MouseMode.ABSOLUTE;
        };
        // Retained for the code paths that only care whether touch is indirect
        config.touchscreenTrackpad = config.mouseMode == MouseMode.RELATIVE ||
                config.mouseMode == MouseMode.TRACKPAD;
        config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST);
        config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE);
        config.enableAAudio = prefs.getBoolean(ENABLE_AAUDIO_PREF_STRING, DEFAULT_ENABLE_AAUDIO);
        config.continuousAudio = prefs.getBoolean(CONTINUOUS_AUDIO_PREF_STRING, DEFAULT_CONTINUOUS_AUDIO);
        config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE);
        config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE);
        config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE);
        config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS);

        return config;
    }
}
