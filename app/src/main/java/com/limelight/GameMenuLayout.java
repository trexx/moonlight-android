package com.limelight;

import java.util.ArrayList;
import java.util.List;

/**
 * Which rows the in-stream menu offers, and in what order.
 *
 * <p>Split out of {@link GameMenu} so the shape of the menu can be tested. GameMenu itself needs a
 * live {@code Game} activity, an {@code NvConnection} and a claimed USB pad, none of which a JVM
 * test can produce - the same reason {@code VideoStats}, {@code GlRendererParser} and
 * {@code StickCalibration} were separated from what they serve. Only the decision moves here;
 * labels and actions stay in GameMenu, because those are what need a Context.
 *
 * <p>Deliberately free of Android imports and of {@code LimeLog}, which is backed by
 * {@code android.util.Log} and throws under the stubbed android.jar the JVM tests run against.
 *
 * <p>The menu is shown during a stream, so its row count is a cost the user pays mid-game: the
 * three controller options are one row that opens a submenu rather than three at the top level,
 * and that row is absent entirely when none of them applies.
 */
public final class GameMenuLayout {

    /**
     * What the menu needs to know about the session to decide its shape.
     *
     * @param fromGamepad             the menu was opened by holding Start on a pad rather than by
     *                                the Back button. Only then is there a device whose own
     *                                options - the mouse emulation toggle - can be offered.
     * @param hasDongle               an Xbox wireless adapter is claimed and running, so pairing
     *                                is something this build can actually start
     * @param hasGipPads              at least one GIP pad is present, by adapter or by cable
     * @param padAudioFormatSupported the stream's audio format is one the pad sink can convert;
     *                                without it every row in the submenu would refuse
     */
    public record State(boolean fromGamepad, boolean hasDongle, boolean hasGipPads,
                        boolean padAudioFormatSupported) {
    }

    /** A row of the menu. The order of the enum is not the order of the menu. */
    public enum Row {
        /** Top level. */
        KEYBOARD,
        SEND_KEYS,
        CONTROLLERS,
        PERF_OVERLAY,
        DISCONNECT,
        CANCEL,

        /** Inside {@link #CONTROLLERS}. */
        MOUSE_EMULATION,
        PAIR_XBOX,
        PAD_AUDIO,
        BACK
    }

    private GameMenuLayout() {
    }

    /**
     * The top-level menu.
     *
     * <p>Keyboard first and Disconnect last are both deliberate: the first is the most reached for,
     * and the last is the one whose mis-selection costs a session.
     */
    public static List<Row> rootRows(State state) {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.KEYBOARD);
        rows.add(Row.SEND_KEYS);
        if (hasControllerOptions(state)) {
            rows.add(Row.CONTROLLERS);
        }
        rows.add(Row.PERF_OVERLAY);
        rows.add(Row.DISCONNECT);
        rows.add(Row.CANCEL);

        return rows;
    }

    /**
     * The Controllers submenu, always ending in a way back out.
     *
     * <p>Only meaningful when {@link #hasControllerOptions} holds; otherwise the caller should not
     * be offering the row at all, and this returns Back alone.
     */
    public static List<Row> controllerRows(State state) {
        List<Row> rows = new ArrayList<>();

        if (state.fromGamepad()) {
            rows.add(Row.MOUSE_EMULATION);
        }
        if (state.hasDongle()) {
            rows.add(Row.PAIR_XBOX);
        }
        if (padAudioAvailable(state)) {
            rows.add(Row.PAD_AUDIO);
        }
        rows.add(Row.BACK);

        return rows;
    }

    /**
     * Whether the Controllers row would lead anywhere.
     *
     * <p>A menu opened from the Back button on a box with no pad attached has none of these, and
     * offering a row that opens a screen holding only "Back" is worse than not offering it.
     */
    public static boolean hasControllerOptions(State state) {
        return state.fromGamepad() || state.hasDongle() || padAudioAvailable(state);
    }

    /** Pad audio needs a pad to send to and a format it can be converted into. */
    private static boolean padAudioAvailable(State state) {
        return state.hasGipPads() && state.padAudioFormatSupported();
    }

    /** {@link #padNumber} for a pad the host has not been told about yet. */
    public static final int PAD_UNNUMBERED = 0;

    /**
     * The number a pad-audio row shows for a pad: the host's player number, so it is the number
     * the game on the host uses for that pad, and the same one the battery label along the
     * bottom of the screen uses. The rows used to count pads by their position in the driver's
     * list, which disagreed with both whenever an Android-enumerated pad held a slot.
     *
     * <p>Two cases fall back. A pad is numbered on its first input report, not before, so a
     * paired pad nobody has touched has no number yet and is reported as
     * {@link #PAD_UNNUMBERED} for the row to say so - pressing anything on it numbers it. With
     * multi-controller off every pad is player 0, so player numbers would make every row
     * "Controller 1"; that mode keeps the positional count, which at least tells the rows apart.
     *
     * @param multiController the multi-controller setting
     * @param playerNumber    the pad's 0-based player number, or negative if it has none yet
     * @param position        the pad's 1-based position in the driver's list
     * @return the 1-based number to show, or {@link #PAD_UNNUMBERED}
     */
    public static int padNumber(boolean multiController, int playerNumber, int position) {
        if (!multiController) {
            return position;
        }
        return playerNumber < 0 ? PAD_UNNUMBERED : playerNumber + 1;
    }
}
