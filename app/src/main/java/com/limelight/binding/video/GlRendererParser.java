package com.limelight.binding.video;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifies the GPU, and by extension the SoC, from the {@code GL_RENDERER} string.
 *
 * <p>Android exposes no direct way to ask which SoC it is running on that is specific enough to
 * tell an Adreno 505 from an Adreno 630, so {@link MediaCodecHelper} infers it from the GL renderer
 * string instead. The decoder quirks keyed off these answers - low latency options, reference frame
 * invalidation, slice counts - all depend on getting the model number out correctly.
 *
 * <p>Split out of {@link MediaCodecHelper} rather than left there because that class initialises a
 * static field from {@code Build.HARDWARE}, which makes it unloadable on a plain JVM and so
 * untestable off-device. Nothing here touches Android - deliberately including {@code LimeLog},
 * which is backed by {@code android.util.Log} and would throw against the stubbed android.jar that
 * unit tests compile against. The one log line this logic used to emit now sits at the call site.
 *
 * <p>These functions run during {@code MediaCodecHelper.initialize()} only, never per frame.
 */
final class GlRendererParser {

    /**
     * Matches the last three-digit run in the string, because the first group is greedy and gives
     * back only as much as it must. That is what lets "OpenGL ES 3.2 Adreno (TM) 640" resolve to
     * 640 rather than to the digits in the GLES version.
     */
    private static final Pattern MODEL_NUMBER_PATTERN = Pattern.compile("(.*)([0-9]{3})(.*)");

    private GlRendererParser() {
    }

    static boolean isPowerVR(String glRenderer) {
        return glRenderer.toLowerCase(Locale.ROOT).contains("powervr");
    }

    /**
     * @return the three-digit Adreno model number as text, or {@code null} if this is not an Adreno
     *         renderer or carries no recognisable model number
     */
    static String getAdrenoVersionString(String glRenderer) {
        glRenderer = glRenderer.toLowerCase(Locale.ROOT).trim();

        if (!glRenderer.contains("adreno")) {
            return null;
        }

        Matcher matcher = MODEL_NUMBER_PATTERN.matcher(glRenderer);
        if (!matcher.matches()) {
            return null;
        }

        return matcher.group(2);
    }

    static boolean isLowEndSnapdragonRenderer(String glRenderer) {
        String modelNumber = getAdrenoVersionString(glRenderer);
        if (modelNumber == null) {
            // Not an Adreno GPU
            return false;
        }

        // The current logic is to identify low-end SoCs based on a zero in the x0x place.
        return modelNumber.charAt(1) == '0';
    }

    /** @return the Adreno model number, or -1 if this is not an Adreno renderer */
    static int getAdrenoRendererModelNumber(String glRenderer) {
        String modelNumber = getAdrenoVersionString(glRenderer);
        if (modelNumber == null) {
            // Not an Adreno GPU
            return -1;
        }

        return Integer.parseInt(modelNumber);
    }

    // This is a workaround for some broken devices that report
    // only GLES 3.0 even though the GPU is an Adreno 4xx series part.
    // An example of such a device is the Huawei Honor 5x with the
    // Snapdragon 616 SoC (Adreno 405).
    static boolean isGLES31SnapdragonRenderer(String glRenderer) {
        // Snapdragon 4xx and higher support GLES 3.1
        return getAdrenoRendererModelNumber(glRenderer) >= 400;
    }
}
