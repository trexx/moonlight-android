package com.limelight.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.limelight.grid.BrowseMenuLayout.AppRow;
import com.limelight.grid.BrowseMenuLayout.AppState;
import com.limelight.grid.BrowseMenuLayout.HostRow;
import com.limelight.grid.BrowseMenuLayout.HostState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

/**
 * Tests for the shape of the browse screen's two menus.
 *
 * <p>These were unreachable before the decision was split out of {@code BrowseActivity}: the rows
 * were added straight to a framework {@code ContextMenu}, which needs a live activity and a long
 * press on a real device. What is pinned here is which rows appear for a given host or app state,
 * in what order, and - the parts that were easy to get wrong - that a row which would refuse to do
 * anything is not offered at all.
 */
class BrowseMenuLayoutTest {

    @Nested
    @DisplayName("the host menu")
    class HostRows {

        /** A host that is answering, paired, and idle: the ordinary case. */
        private static HostState idle() {
            return new HostState(true, true, false);
        }

        @Test
        @DisplayName("offers only pairing on a host that is answering but not paired")
        void unpairedOnlineGetsPair() {
            List<HostRow> rows = BrowseMenuLayout.hostRows(new HostState(true, false, false));

            assertEquals(List.of(HostRow.PAIR, HostRow.DELETE, HostRow.DETAILS), rows);
        }

        /**
         * Unpair is a request to the host, so it needs one that is answering, and it means nothing
         * for a host we were never paired with. Both tests used to be written out separately in
         * BrowseActivity, one of them as "neither offline nor unknown".
         */
        @Test
        @DisplayName("offers unpairing only on a host that is both online and paired")
        void unpairNeedsOnlineAndPaired() {
            assertTrue(BrowseMenuLayout.hostRows(idle()).contains(HostRow.UNPAIR));

            assertFalse(BrowseMenuLayout.hostRows(new HostState(false, true, false))
                    .contains(HostRow.UNPAIR), "offline and paired");
            assertFalse(BrowseMenuLayout.hostRows(new HostState(true, false, false))
                    .contains(HostRow.UNPAIR), "online and unpaired");
        }

        @Test
        @DisplayName("drops to the three rows that work on a host that is not answering")
        void offlineHost() {
            assertEquals(List.of(HostRow.APP_LIST, HostRow.DELETE, HostRow.DETAILS),
                    BrowseMenuLayout.hostRows(new HostState(false, true, false)));
        }

        @Test
        @DisplayName("adds Resume and Quit, in that order, when a session is running")
        void runningSession() {
            assertEquals(
                    List.of(HostRow.RESUME, HostRow.QUIT, HostRow.APP_LIST, HostRow.UNPAIR,
                            HostRow.DELETE, HostRow.DETAILS),
                    BrowseMenuLayout.hostRows(new HostState(true, true, true)));
        }

        @Test
        @DisplayName("never offers a session row on a host with nothing running")
        void noSessionRows() {
            for (HostState state : List.of(idle(), new HostState(false, true, false),
                    new HostState(true, false, false), new HostState(false, false, false))) {
                List<HostRow> rows = BrowseMenuLayout.hostRows(state);

                assertFalse(rows.contains(HostRow.RESUME), "for " + state);
                assertFalse(rows.contains(HostRow.QUIT), "for " + state);
            }
        }

        /**
         * Delete removes the host outright, so it must not move under the cursor as rows above it
         * come and go with the host's state.
         */
        @ParameterizedTest(name = "online={0} paired={1} running={2}")
        @DisplayName("ends with Delete then Details whatever the host is doing")
        @CsvSource({
                "true,  true,  true",
                "true,  true,  false",
                "true,  false, false",
                "false, true,  true",
                "false, true,  false",
                "false, false, false",
        })
        void deleteAndDetailsLast(boolean online, boolean paired, boolean running) {
            List<HostRow> rows = BrowseMenuLayout.hostRows(new HostState(online, paired, running));

            assertEquals(HostRow.DELETE, rows.get(rows.size() - 2));
            assertEquals(HostRow.DETAILS, rows.get(rows.size() - 1));
        }

        @Test
        @DisplayName("never offers Pair and Unpair together")
        void pairAndUnpairAreExclusive() {
            for (boolean online : List.of(true, false)) {
                for (boolean paired : List.of(true, false)) {
                    List<HostRow> rows = BrowseMenuLayout.hostRows(
                            new HostState(online, paired, false));

                    assertFalse(rows.contains(HostRow.PAIR) && rows.contains(HostRow.UNPAIR),
                            "online=" + online + " paired=" + paired);
                }
            }
        }
    }

    @Nested
    @DisplayName("the app menu")
    class AppRows {

        /** An app on a host with nothing running, box art loaded, not hidden. */
        private static AppState idle() {
            return new AppState(false, false, false, true);
        }

        @Test
        @DisplayName("offers Resume and Quit for the app that is running")
        void thisAppRunning() {
            assertEquals(List.of(AppRow.RESUME, AppRow.QUIT, AppRow.DETAILS, AppRow.SHORTCUT),
                    BrowseMenuLayout.appRows(new AppState(true, true, false, true)));
        }

        @Test
        @DisplayName("offers quit-and-start when a different app is running")
        void anotherAppRunning() {
            assertEquals(
                    List.of(AppRow.QUIT_AND_START, AppRow.HIDE, AppRow.DETAILS, AppRow.SHORTCUT),
                    BrowseMenuLayout.appRows(new AppState(true, false, false, true)));
        }

        @Test
        @DisplayName("offers no session row when nothing is running")
        void nothingRunning() {
            assertEquals(List.of(AppRow.HIDE, AppRow.DETAILS, AppRow.SHORTCUT),
                    BrowseMenuLayout.appRows(idle()));
        }

        /**
         * One row, worded for the state the app is in. There is no checkbox to carry the state
         * instead: the shared menu row is a plain text row.
         */
        @Test
        @DisplayName("says Show rather than Hide for an app already hidden")
        void hiddenAppOffersShow() {
            List<AppRow> rows = BrowseMenuLayout.appRows(new AppState(false, false, true, true));

            assertTrue(rows.contains(AppRow.UNHIDE));
            assertFalse(rows.contains(AppRow.HIDE));
        }

        /**
         * Hiding the app that is running would take the one thing the user is most likely to come
         * back to off the grid. Showing it again stays available, so an app hidden before it was
         * started can still be got back.
         */
        @Test
        @DisplayName("will not hide the running app, but will still show it")
        void runningAppHideRules() {
            List<AppRow> visible = BrowseMenuLayout.appRows(new AppState(true, true, false, true));
            assertFalse(visible.contains(AppRow.HIDE));
            assertFalse(visible.contains(AppRow.UNHIDE));

            assertTrue(BrowseMenuLayout.appRows(new AppState(true, true, true, true))
                    .contains(AppRow.UNHIDE));
        }

        @Test
        @DisplayName("offers a shortcut only once box art has loaded, and always last")
        void shortcutNeedsBoxArt() {
            List<AppRow> withArt = BrowseMenuLayout.appRows(idle());
            assertEquals(AppRow.SHORTCUT, withArt.get(withArt.size() - 1));

            assertFalse(BrowseMenuLayout.appRows(new AppState(false, false, false, false))
                    .contains(AppRow.SHORTCUT));
        }

        @ParameterizedTest(name = "running={0} thisApp={1} hidden={2} boxArt={3}")
        @DisplayName("always offers Details")
        @CsvSource({
                "true,  true,  true,  true",
                "true,  false, false, false",
                "false, false, true,  false",
                "false, false, false, true",
        })
        void detailsAlwaysPresent(boolean running, boolean thisApp, boolean hidden,
                                  boolean boxArt) {
            assertTrue(BrowseMenuLayout.appRows(new AppState(running, thisApp, hidden, boxArt))
                    .contains(AppRow.DETAILS));
        }
    }
}
