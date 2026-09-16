package com.limelight.binding.input.driver;

/**
 * The user's guide button LED choice for the Xbox pads this app drives, and the ladder that turns
 * a battery level into an LED command when the choice is to follow the battery.
 *
 * <p>Every LED number on the Java side lives here. The values are the protocol's own, not
 * percentages of anything we chose: MS-GIPUSB 3.1.5.5.7 Table 41 defines the LED command's
 * intensity byte as 0 to 47 ({@code 0x2F}), and Table 42 its pattern byte. Keeping the whole
 * translation on this side of the JNI boundary keeps it testable; the native side only applies
 * what it is handed, and rejects a pattern it does not know.
 *
 * <p>Split out of {@link GipController}, which loads a native library in its static initialiser
 * and so cannot be touched from a JVM test, following {@link StickCalibration}. Android-free.
 *
 * <p>Latency: none. Read once per pad at start and once per battery report, on the driver's own
 * thread; never on the frame or input path.
 *
 * @param intensity      what the pad is told at start, Table 41 units
 * @param followsBattery whether battery reports move it after that
 */
public record GuideButtonLed(int intensity, boolean followsBattery) {

    // Table 41 intensities. The presets, and the ceiling. xow believed the maximum was 0x20, so
    // whether a pad honours anything above that is an open hardware question - see
    // HARDWARE_TESTING.md 22.
    public static final int INTENSITY_OFF = 0x00;
    public static final int INTENSITY_DIM = 0x0A;
    public static final int INTENSITY_NORMAL = 0x14;
    public static final int INTENSITY_BRIGHT = 0x2F;
    public static final int INTENSITY_MAX = 0x2F;

    // Table 42 patterns. These mirror enum LedMode in app/src/main/jni/xow_driver/controller/gip.h
    // and must stay equal to it: the native side accepts exactly this set and refuses anything
    // else, which is what catches the two drifting apart.
    public static final int PATTERN_OFF = 0x00;
    public static final int PATTERN_ON = 0x01;
    public static final int PATTERN_BLINK_FAST = 0x02;
    public static final int PATTERN_BLINK_MED = 0x03;
    public static final int PATTERN_BLINK_SLOW = 0x04;
    public static final int PATTERN_FADE_SLOW = 0x08;
    public static final int PATTERN_FADE_FAST = 0x09;

    /** What the driver sent before the setting existed, and what anything unrecognised gets. */
    public static final GuideButtonLed NORMAL = new GuideButtonLed(INTENSITY_NORMAL, false);

    /**
     * One LED command's worth: what the pad is told to show.
     *
     * @param pattern   one of {@code PATTERN_*}
     * @param intensity 0 to {@link #INTENSITY_MAX}
     */
    public record State(int pattern, int intensity) {
    }

    /**
     * @return what the driver sends when the pad starts. Pattern and intensity are separate
     *         fields (Table 42), so an intensity of zero on a lit pattern is not what the spec
     *         means by off: zero selects the off pattern rather than a maximally dimmed on one.
     */
    public State startState() {
        return new State(intensity == 0 ? PATTERN_OFF : PATTERN_ON, intensity);
    }

    /**
     * The ladder: the LED for a battery report, when the setting is to follow it.
     *
     * <p>The steps are the presets, so each one is a brightness the user has already seen on the
     * other settings: full is Bright, medium is Normal, low is Dim. Critical flashes rather than
     * dimming further, because a dim LED across a room is indistinguishable from the Dim preset
     * and a warning has to look like one; it flashes at Normal so the flash is visible. A pad with
     * no battery fitted is running off its cable, and gets full brightness for the same reason a
     * full battery does.
     *
     * <p>Only critical uses a pattern other than On. Table 42 marks every other pattern "not
     * implemented by host", which describes Microsoft's host rather than the pad, so whether a
     * pad honours the slow blink is a hardware question - HARDWARE_TESTING.md 31.
     *
     * @param type  GIP battery type: 0 absent or bus powered, 1 standard, 2 rechargeable
     * @param level GIP battery level, 0 critical to 3 full; meaningless when {@code type} is 0
     * @return the command to send; a level the spec does not define gets what the pad was
     *         started with, so an unexpected value never changes anything
     */
    public static State forBattery(byte type, byte level) {
        if (type == 0) {
            return new State(PATTERN_ON, INTENSITY_BRIGHT);
        }
        return switch (level) {
            case 3 -> new State(PATTERN_ON, INTENSITY_BRIGHT);
            case 2 -> new State(PATTERN_ON, INTENSITY_NORMAL);
            case 1 -> new State(PATTERN_ON, INTENSITY_DIM);
            case 0 -> new State(PATTERN_BLINK_SLOW, INTENSITY_NORMAL);
            default -> NORMAL.startState();
        };
    }
}
