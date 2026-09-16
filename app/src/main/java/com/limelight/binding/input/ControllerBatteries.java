package com.limelight.binding.input;

import com.limelight.nvstream.jni.MoonBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * The last battery reading of every attached controller, kept so the in-game menu can show them.
 *
 * <p>Split out of {@link ControllerHandler} for the same reason {@link ControllerLedColor} is: the
 * handler needs a real {@code InputDevice} and cannot be loaded from a JVM test. Battery state used
 * to be fire-and-forget to the host, so nothing on the box could show it; this is the one place it
 * is retained, and the only consumer is the label the menu shows.
 *
 * <p>Slots are indexed by host player number rather than device id, because that is the number the
 * user sees on the host and the one the label prints. The attached-controller mask is a
 * {@code short}, which is where {@link #MAX_CONTROLLERS} comes from.
 *
 * <p>Written from two threads that never meet the reader: a GIP pad reports from its driver's read
 * thread, an Android-enumerated pad from the battery {@code HandlerThread}, and the label reads on
 * the UI thread once per menu level. {@code synchronized} is the right tool at those rates — a few
 * writes per session, one read per menu open — and keeps the snapshot consistent without any
 * cleverness.
 *
 * <p>References {@code MoonBridge.LI_BATTERY_*} freely: they are {@code static final byte}
 * compile-time constants, so {@code javac} inlines them and this class never triggers
 * {@code MoonBridge}'s static initialiser, which loads a native library and would kill a JVM test.
 * Nothing else in {@code MoonBridge} may be touched from here.
 *
 * <p>Latency: none. Nothing here is on the frame path or the input path.
 */
public final class ControllerBatteries {

    /** One more than the highest player number; the attached-controller mask is 16 bits wide. */
    public static final int MAX_CONTROLLERS = 16;

    /**
     * What a reading is worth showing as. {@link #HIDDEN} never leaves {@link #snapshot()}: it is
     * the classifier's way of saying the reading carries nothing the user can act on.
     */
    public enum Display {
        /** A percentage, discharging or otherwise not being filled. */
        LEVEL,
        /** A percentage, and the pad is charging. */
        CHARGING,
        /** No battery level at all, but the pad is on a cable — a GIP pad with no battery pack. */
        WIRED,
        /** Nothing to show. */
        HIDDEN
    }

    /**
     * One controller worth showing.
     *
     * @param controllerNumber host player number, 0-based
     * @param display          what to show it as; never {@link Display#HIDDEN}
     * @param percentage       0 to 100; only meaningful for {@link Display#LEVEL} and
     *                         {@link Display#CHARGING}
     */
    public record Reading(int controllerNumber, Display display, int percentage) {
    }

    // Moonlight's own units, exactly as they go to the host, so the label and the host never
    // disagree about a pad. Percentage is 0 - 100 or LI_BATTERY_PERCENTAGE_UNKNOWN.
    private final byte[] states = new byte[MAX_CONTROLLERS];
    private final byte[] percentages = new byte[MAX_CONTROLLERS];
    private final boolean[] present = new boolean[MAX_CONTROLLERS];

    /**
     * Records a reading, replacing whatever the slot held.
     *
     * <p>An out-of-range number is ignored rather than thrown on: this is fed from driver threads,
     * and an exception there takes the pad's read loop down with it.
     *
     * @param controllerNumber host player number
     * @param state            one of {@code MoonBridge.LI_BATTERY_STATE_*}
     * @param percentage       0 to 100, or {@code MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN}
     */
    public synchronized void update(int controllerNumber, byte state, byte percentage) {
        if (controllerNumber < 0 || controllerNumber >= MAX_CONTROLLERS) {
            return;
        }
        states[controllerNumber] = state;
        percentages[controllerNumber] = percentage;
        present[controllerNumber] = true;
    }

    /** Forgets a slot, for a controller whose player number has been released. */
    public synchronized void clear(int controllerNumber) {
        if (controllerNumber < 0 || controllerNumber >= MAX_CONTROLLERS) {
            return;
        }
        present[controllerNumber] = false;
    }

    /**
     * @return every controller with something to show, in ascending player number. Allocates a
     *         small list per call, which is fine: it is called once per menu level, never per frame.
     */
    public synchronized List<Reading> snapshot() {
        List<Reading> readings = new ArrayList<>();
        for (int i = 0; i < MAX_CONTROLLERS; i++) {
            if (!present[i]) {
                continue;
            }
            Display display = classify(states[i], percentages[i]);
            if (display == Display.HIDDEN) {
                continue;
            }
            readings.add(new Reading(i, display, percentages[i] & 0xFF));
        }
        return readings;
    }

    /**
     * Decides what a reading shows as. Package-private so the table can be tested directly.
     *
     * <p>The unknown-percentage cases are the non-obvious ones. A GIP pad running off its cable
     * with no battery pack reports {@code NOT_CHARGING} with an unknown percentage, because the spec
     * gives it no level to report; the only fact in that reading is that it is on a cable, which is
     * still worth a word. {@code CHARGING} with no percentage is the same fact. Everything else
     * without a percentage says nothing.
     *
     * <p>With a percentage, only {@code CHARGING} needs distinguishing: {@code DISCHARGING},
     * {@code FULL}, {@code NOT_CHARGING} and even {@code UNKNOWN} — which Android's
     * {@code BatteryState} can report alongside a real capacity — all just show the level.
     */
    static Display classify(byte state, byte percentage) {
        if (state == MoonBridge.LI_BATTERY_STATE_NOT_PRESENT) {
            return Display.HIDDEN;
        }
        if (percentage == MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN) {
            return switch (state) {
                case MoonBridge.LI_BATTERY_STATE_CHARGING,
                     MoonBridge.LI_BATTERY_STATE_NOT_CHARGING -> Display.WIRED;
                default -> Display.HIDDEN;
            };
        }
        return state == MoonBridge.LI_BATTERY_STATE_CHARGING ? Display.CHARGING : Display.LEVEL;
    }
}
