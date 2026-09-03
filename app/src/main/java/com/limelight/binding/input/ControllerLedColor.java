package com.limelight.binding.input;

/**
 * Packs the host's light bar colour into the ARGB integer the platform lights API wants.
 *
 * <p>Split out of {@link ControllerHandler} for the same reason the rest of this codebase's pure
 * logic is: {@code ControllerHandler} needs a real {@code InputDevice} and a {@code LightsManager}
 * and cannot be loaded from a JVM test at all. This is the one part of the LED path whose
 * correctness does not depend on hardware being present, so it is the part worth pinning.
 *
 * <p>The masks are the whole point of the class. moonlight-common-c hands the components over as
 * signed Java bytes, so anything above 0x7F arrives negative and {@code r << 16} sign-extends
 * across the top of the word — full-brightness red would set every bit above bit 16 and reach the
 * controller as white. Each component is masked back down to its own byte after shifting.
 *
 * <p>Latency: none. Called once per host LED callback, which fires when a game changes the light
 * bar. It is not on the frame path or the input path.
 */
final class ControllerLedColor {

    private ControllerLedColor() {
    }

    /**
     * @param r red component, as the host sent it
     * @param g green component, as the host sent it
     * @param b blue component, as the host sent it
     * @return the opaque ARGB value for {@code LightState.Builder.setColor()}
     */
    static int toArgb(byte r, byte g, byte b) {
        return 0xFF000000 | ((r << 16) & 0xFF0000) | ((g << 8) & 0xFF00) | (b & 0xFF);
    }
}
