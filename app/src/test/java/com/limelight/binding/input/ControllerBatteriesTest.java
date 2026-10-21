package com.limelight.binding.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.limelight.binding.input.ControllerBatteries.Display;
import com.limelight.binding.input.ControllerBatteries.Reading;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.List;

/**
 * Tests for the store behind the in-game menu's battery label.
 *
 * <p>{@code MoonBridge.LI_BATTERY_*} are compile-time constants, so referencing them here inlines
 * them and never loads {@code MoonBridge}, whose static initialiser would call
 * {@code System.loadLibrary}. Nothing else in that class may be touched from a test.
 */
class ControllerBatteriesTest {

    private static final byte UNKNOWN = MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN;

    @Nested
    @DisplayName("classify()")
    class Classification {

        /**
         * The whole table. The unknown-percentage rows are the ones worth reading: a GIP pad with
         * no battery pack reports {@code NOT_CHARGING} and no percentage, and the only fact in
         * that is the cable, which is still worth a word; anything else without a percentage
         * says nothing. Android can report {@code UNKNOWN} with a real capacity, which is a
         * level, not nothing.
         */
        @ParameterizedTest(name = "state {0} percentage {1} -> {2}")
        @CsvSource({
                "2,  75,  LEVEL",     // discharging
                "5,  100, LEVEL",     // full
                "0,  40,  LEVEL",     // unknown state, known level
                "4,  60,  LEVEL",     // not charging, known level
                "3,  50,  CHARGING",
                "4,  -1,  WIRED",     // not charging, no level: a pad on its cable
                "3,  -1,  WIRED",     // charging, no level
                "0,  -1,  HIDDEN",
                "2,  -1,  HIDDEN",
                "1,  50,  HIDDEN",    // not present
        })
        @DisplayName("each reading classifies as the table says")
        void classifiesAsTheTableSays(byte state, byte percentage, Display expected) {
            assertEquals(expected, ControllerBatteries.classify(state, percentage));
        }
    }

    @Nested
    @DisplayName("snapshot()")
    class Snapshot {

        @Test
        @DisplayName("an empty store yields an empty list")
        void emptyStoreYieldsEmptyList() {
            assertTrue(new ControllerBatteries().snapshot().isEmpty());
        }

        /**
         * Several pads at once is the case the label exists for. They come back in player order
         * whatever order they reported in, each with its own display kind.
         */
        @Test
        @DisplayName("mixed controllers come back in player order")
        void mixedControllersComeBackInPlayerOrder() {
            ControllerBatteries batteries = new ControllerBatteries();
            batteries.update(3, MoonBridge.LI_BATTERY_STATE_NOT_CHARGING, UNKNOWN);
            batteries.update(0, MoonBridge.LI_BATTERY_STATE_DISCHARGING, (byte) 75);
            batteries.update(2, MoonBridge.LI_BATTERY_STATE_CHARGING, (byte) 50);

            assertEquals(List.of(
                            new Reading(0, Display.LEVEL, 75),
                            new Reading(2, Display.CHARGING, 50),
                            new Reading(3, Display.WIRED, UNKNOWN & 0xFF)),
                    batteries.snapshot());
        }

        /** A reading with nothing to say is not a row; the label would only print "unknown". */
        @Test
        @DisplayName("readings with nothing to show are excluded")
        void unknownReadingsAreExcluded() {
            ControllerBatteries batteries = new ControllerBatteries();
            batteries.update(0, MoonBridge.LI_BATTERY_STATE_DISCHARGING, UNKNOWN);
            batteries.update(1, MoonBridge.LI_BATTERY_STATE_NOT_PRESENT, (byte) 50);

            assertTrue(batteries.snapshot().isEmpty());
        }

        /** A pad that unplugged must not linger on the label under its old number. */
        @Test
        @DisplayName("a cleared controller disappears")
        void removedControllerDisappears() {
            ControllerBatteries batteries = new ControllerBatteries();
            batteries.update(1, MoonBridge.LI_BATTERY_STATE_DISCHARGING, (byte) 75);
            batteries.clear(1);

            assertTrue(batteries.snapshot().isEmpty());
        }

        /** The number a pad vacated goes to the next pad; the slot must take the new reading. */
        @Test
        @DisplayName("a re-added controller reuses its slot")
        void reAddedControllerReusesItsSlot() {
            ControllerBatteries batteries = new ControllerBatteries();
            batteries.update(1, MoonBridge.LI_BATTERY_STATE_DISCHARGING, (byte) 75);
            batteries.clear(1);
            batteries.update(1, MoonBridge.LI_BATTERY_STATE_CHARGING, (byte) 20);

            assertEquals(List.of(new Reading(1, Display.CHARGING, 20)), batteries.snapshot());
        }

        @Test
        @DisplayName("the latest update wins")
        void latestUpdateWins() {
            ControllerBatteries batteries = new ControllerBatteries();
            batteries.update(0, MoonBridge.LI_BATTERY_STATE_DISCHARGING, (byte) 75);
            batteries.update(0, MoonBridge.LI_BATTERY_STATE_DISCHARGING, (byte) 74);

            assertEquals(List.of(new Reading(0, Display.LEVEL, 74)), batteries.snapshot());
        }

        /**
         * The percentage travels as a Java byte, which is signed. 100 fits, but the arithmetic
         * that reads it back must not sign-extend anything, or a value over 127 - which the
         * unknown marker is - would come back negative.
         */
        @Test
        @DisplayName("the percentage is read unsigned")
        void percentageIsUnsigned() {
            ControllerBatteries batteries = new ControllerBatteries();
            batteries.update(0, MoonBridge.LI_BATTERY_STATE_FULL, (byte) 100);
            batteries.update(1, MoonBridge.LI_BATTERY_STATE_CHARGING, UNKNOWN);

            List<Reading> readings = batteries.snapshot();
            assertEquals(100, readings.get(0).percentage());
            assertEquals(0xFF, readings.get(1).percentage());
        }

        /**
         * Fed from driver threads, where an exception takes the pad's read loop down. A number
         * outside the slots is dropped, not thrown on, and leaves nothing behind.
         */
        @Test
        @DisplayName("out-of-range controller numbers are ignored")
        void outOfRangeNumbersAreIgnored() {
            ControllerBatteries batteries = new ControllerBatteries();
            batteries.update(-1, MoonBridge.LI_BATTERY_STATE_DISCHARGING, (byte) 75);
            batteries.update(ControllerBatteries.MAX_CONTROLLERS,
                    MoonBridge.LI_BATTERY_STATE_DISCHARGING, (byte) 75);
            batteries.clear(-1);
            batteries.clear(ControllerBatteries.MAX_CONTROLLERS);

            assertTrue(batteries.snapshot().isEmpty());
        }
    }
}
