package com.limelight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.limelight.GameMenuLayout.Row;
import com.limelight.GameMenuLayout.State;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

/**
 * Tests for the shape of the in-stream menu.
 *
 * <p>The menu is shown during a stream, so what it costs the user is the number of rows between
 * them and what they came for. These pin that: which rows appear, in what order, and - the part
 * worth guarding - when the Controllers row is not offered at all.
 */
class GameMenuLayoutTest {

    /** The menu as it appears when opened with the Back button on a box with nothing attached. */
    private static State bare() {
        return new State(false, false, false, false);
    }

    @Nested
    @DisplayName("the top level")
    class RootRows {

        @Test
        @DisplayName("is six rows when every controller option applies")
        void fullMenu() {
            assertEquals(
                    List.of(Row.KEYBOARD, Row.SEND_KEYS, Row.CONTROLLERS, Row.PERF_OVERLAY,
                            Row.DISCONNECT, Row.CANCEL),
                    GameMenuLayout.rootRows(new State(true, true, true, true)));
        }

        @Test
        @DisplayName("drops to five when no controller option applies")
        void bareMenu() {
            assertEquals(
                    List.of(Row.KEYBOARD, Row.SEND_KEYS, Row.PERF_OVERLAY, Row.DISCONNECT,
                            Row.CANCEL),
                    GameMenuLayout.rootRows(bare()));
        }

        /**
         * Disconnect ends a session, so it stays put: a row that appears or disappears above it
         * would move it under the cursor of someone reaching for Cancel.
         */
        @Test
        @DisplayName("keeps Disconnect second from last whatever else is shown")
        void disconnectStaysPenultimate() {
            for (State state : List.of(bare(), new State(true, true, true, true),
                    new State(false, true, false, false), new State(true, false, true, true))) {
                List<Row> rows = GameMenuLayout.rootRows(state);

                assertEquals(Row.DISCONNECT, rows.get(rows.size() - 2), "for " + state);
                assertEquals(Row.CANCEL, rows.get(rows.size() - 1), "for " + state);
            }
        }

        @Test
        @DisplayName("never offers a controller row that leads nowhere")
        void controllersRowMatchesItsContents() {
            for (boolean fromGamepad : new boolean[]{false, true}) {
                for (boolean dongle : new boolean[]{false, true}) {
                    for (boolean pads : new boolean[]{false, true}) {
                        for (boolean format : new boolean[]{false, true}) {
                            State state = new State(fromGamepad, dongle, pads, format);

                            boolean offered = GameMenuLayout.rootRows(state).contains(Row.CONTROLLERS);
                            boolean hasContents = GameMenuLayout.controllerRows(state).size() > 1;

                            assertEquals(hasContents, offered,
                                    "the Controllers row and its contents disagree for " + state);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("the Controllers submenu")
    class ControllerRows {

        @Test
        @DisplayName("always ends in a way back out")
        void alwaysEndsWithBack() {
            for (boolean fromGamepad : new boolean[]{false, true}) {
                for (boolean dongle : new boolean[]{false, true}) {
                    State state = new State(fromGamepad, dongle, true, true);
                    List<Row> rows = GameMenuLayout.controllerRows(state);

                    assertEquals(Row.BACK, rows.get(rows.size() - 1), "for " + state);
                }
            }
        }

        /**
         * The mouse emulation toggle is the device's own option, and there is no device unless the
         * menu was opened by holding Start on a pad. Opened from the Back button it must not appear.
         */
        @Test
        @DisplayName("offers mouse emulation only when opened from a gamepad")
        void mouseEmulationNeedsAGamepad() {
            assertTrue(GameMenuLayout.controllerRows(new State(true, false, false, false))
                    .contains(Row.MOUSE_EMULATION));
            assertFalse(GameMenuLayout.controllerRows(new State(false, true, true, true))
                    .contains(Row.MOUSE_EMULATION));
        }

        @Test
        @DisplayName("orders the options the same way every time")
        void orderIsStable() {
            assertEquals(List.of(Row.MOUSE_EMULATION, Row.PAIR_XBOX, Row.PAD_AUDIO, Row.BACK),
                    GameMenuLayout.controllerRows(new State(true, true, true, true)));
        }

        /**
         * Pad audio needs somewhere to send to and a format it can convert; without either, every
         * row of the submenu below it would refuse, so the entry is not offered.
         */
        @ParameterizedTest(name = "pads={0} formatSupported={1} -> offered={2}")
        @CsvSource({
                "true,  true,  true",
                "true,  false, false",
                "false, true,  false",
                "false, false, false",
        })
        @DisplayName("offers pad audio only with a pad and a convertible format")
        void padAudioNeedsBoth(boolean pads, boolean formatSupported, boolean offered) {
            assertEquals(offered,
                    GameMenuLayout.controllerRows(new State(false, false, pads, formatSupported))
                            .contains(Row.PAD_AUDIO));
        }
    }

    @Nested
    @DisplayName("hasControllerOptions")
    class HasControllerOptions {

        @ParameterizedTest(name = "gamepad={0} dongle={1} pads={2} format={3} -> {4}")
        @CsvSource({
                "false, false, false, false, false",
                "true,  false, false, false, true",
                "false, true,  false, false, true",
                "false, false, true,  true,  true",
                // A pad with no convertible audio format is not, on its own, a reason to offer it.
                "false, false, true,  false, false",
                "true,  true,  true,  true,  true",
        })
        @DisplayName("holds exactly when one of the three would be shown")
        void matchesTheRows(boolean fromGamepad, boolean dongle, boolean pads,
                            boolean formatSupported, boolean expected) {
            assertEquals(expected, GameMenuLayout.hasControllerOptions(
                    new State(fromGamepad, dongle, pads, formatSupported)));
        }
    }
}
