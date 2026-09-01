package com.limelight.grid;

import java.util.ArrayList;
import java.util.List;

/**
 * Which rows the browse screen's two menus offer, and in what order.
 *
 * <p>Split out of {@code BrowseActivity} for the same reason {@link BrowseState} was: the
 * decisions were reachable only from a long press on a real device, against a framework
 * {@code ContextMenu} that a JVM test cannot build. Only the decision lives here; labels and
 * actions stay in BrowseActivity, because those need a Context and a bound
 * {@code ComputerManagerService}. This is the precedent {@code GameMenuLayout} set for the
 * in-stream menu, and before it {@code VideoStats}, {@code GlRendererParser} and
 * {@code StickCalibration}.
 *
 * <p>Deliberately free of Android imports and of {@code LimeLog}, which is backed by
 * {@code android.util.Log} and throws under the stubbed android.jar the JVM tests run against.
 * The state records take booleans rather than {@code ComputerDetails} and {@code AppObject} so
 * that this file's testability does not depend on those staying Android-free.
 */
public final class BrowseMenuLayout {

    /**
     * What the host menu needs to know about one host.
     *
     * @param online         the host answered its last poll. The menu previously asked for
     *                       "neither offline nor unknown", which is exactly this - the state enum
     *                       has only those three values - and asked it separately from the
     *                       "is online" test that gates Unpair.
     * @param paired         this client holds a pairing certificate the host accepts
     * @param sessionRunning the host reports a game already running, whether or not it is one of
     *                       ours
     */
    public record HostState(boolean online, boolean paired, boolean sessionRunning) {
    }

    /** A row of the host menu. The order of the enum is not the order of the menu. */
    public enum HostRow {
        PAIR,
        RESUME,
        QUIT,
        APP_LIST,
        UNPAIR,
        DELETE,
        DETAILS
    }

    /**
     * What the app menu needs to know about one app.
     *
     * @param sessionRunning some app is running on the host, so starting this one means quitting
     *                       that one first
     * @param thisAppRunning the running app is this one
     * @param hidden         this app is on the hidden list, so the row offers to show it again
     * @param hasBoxArt      box art has loaded, so there is an image to put on a shortcut
     */
    public record AppState(boolean sessionRunning, boolean thisAppRunning, boolean hidden,
                           boolean hasBoxArt) {
    }

    /** A row of the app menu. The order of the enum is not the order of the menu. */
    public enum AppRow {
        RESUME,
        QUIT,
        QUIT_AND_START,
        HIDE,
        UNHIDE,
        DETAILS,
        SHORTCUT
    }

    private BrowseMenuLayout() {
    }

    /**
     * The host menu.
     *
     * <p>An unpaired host that is answering has exactly one useful action, so it gets Pair and
     * none of the rows that need a paired session. Delete and Details close every version of the
     * menu: they are the two things that work whatever state the host is in, and Delete stays
     * next to last so its position does not move as rows above it come and go.
     */
    public static List<HostRow> hostRows(HostState state) {
        List<HostRow> rows = new ArrayList<>();

        if (state.online() && !state.paired()) {
            rows.add(HostRow.PAIR);
        }
        else {
            if (state.sessionRunning()) {
                rows.add(HostRow.RESUME);
                rows.add(HostRow.QUIT);
            }

            rows.add(HostRow.APP_LIST);

            // Unpair is a request to the host, so it needs one that is answering, and it has no
            // meaning for a host we are not paired with - that case gets Pair, above. Grouped
            // with Delete rather than with the session actions: both undo the setup of this host,
            // and both ask for a confirmation before they run.
            if (state.online() && state.paired()) {
                rows.add(HostRow.UNPAIR);
            }
        }

        rows.add(HostRow.DELETE);
        rows.add(HostRow.DETAILS);

        return rows;
    }

    /**
     * The app menu.
     *
     * <p>The first row is always what selecting the tile itself would do, so the menu and the tile
     * agree: resume this app if it is the one running, quit the other one first if it is not.
     *
     * <p>Hide and Show are separate rows rather than one checkable row, because the shared row
     * layout is a plain text row with no checkbox. A row that says what the app is now is also
     * easier to read at viewing distance than a check mark, which is the convention the in-stream
     * menu's pad-audio rows already use.
     */
    public static List<AppRow> appRows(AppState state) {
        List<AppRow> rows = new ArrayList<>();

        if (state.sessionRunning()) {
            if (state.thisAppRunning()) {
                rows.add(AppRow.RESUME);
                rows.add(AppRow.QUIT);
            }
            else {
                rows.add(AppRow.QUIT_AND_START);
            }
        }

        // Hiding the app that is running would take it off the grid while it is the one thing the
        // user is most likely to come back to. Showing it again is still offered, so an app that
        // was hidden before it was started can be got back.
        if (!state.thisAppRunning() || state.hidden()) {
            rows.add(state.hidden() ? AppRow.UNHIDE : AppRow.HIDE);
        }

        rows.add(AppRow.DETAILS);

        // Only worth offering once there is box art to put on the shortcut.
        if (state.hasBoxArt()) {
            rows.add(AppRow.SHORTCUT);
        }

        return rows;
    }
}
