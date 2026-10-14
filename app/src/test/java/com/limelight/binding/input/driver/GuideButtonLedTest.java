package com.limelight.binding.input.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for the guide LED numbers and the battery ladder.
 *
 * <p>Everything here goes to the pad byte for byte, so the values are pinned rather than
 * described: a step that silently changed would otherwise only be visible on hardware, and the
 * hardware is the one place these are hard to check.
 */
class GuideButtonLedTest {

    @Nested
    @DisplayName("forBattery()")
    class Ladder {

        /**
         * The ladder in full. Type 1 and 2 are the two kinds of battery the spec knows; type 0 is
         * no battery at all, whose level the spec says to ignore. Level 7 does not exist and must
         * change nothing, which means the start state rather than the brightest or dimmest step.
         */
        @ParameterizedTest(name = "type {0} level {1} -> pattern {2} intensity {3}")
        @CsvSource({
                "1, 3, 0x01, 0x2F",
                "1, 2, 0x01, 0x14",
                "1, 1, 0x01, 0x0A",
                "1, 0, 0x04, 0x14",
                "2, 0, 0x04, 0x14",
                "0, 0, 0x01, 0x2F",
                "0, 3, 0x01, 0x2F",
                "1, 7, 0x01, 0x14",
        })
        @DisplayName("each battery report maps to its LED command")
        void ladderIsPinned(byte type, byte level, int pattern, int intensity) {
            assertEquals(new GuideButtonLed.State(pattern, intensity),
                    GuideButtonLed.forBattery(type, level));
        }

        /**
         * The flash is the warning. Any other level flashing would make the warning meaningless,
         * and a flashing full pad would send the user hunting for a charger.
         */
        @Test
        @DisplayName("only the critical level uses a pattern other than on")
        void onlyCriticalBlinks() {
            for (byte level = 1; level <= 3; level++) {
                assertEquals(GuideButtonLed.PATTERN_ON,
                        GuideButtonLed.forBattery((byte) 1, level).pattern(),
                        "level " + level + " should be steady");
            }
            assertEquals(GuideButtonLed.PATTERN_ON,
                    GuideButtonLed.forBattery((byte) 0, (byte) 0).pattern(),
                    "a pad on its cable should be steady");
            assertNotEquals(GuideButtonLed.PATTERN_ON,
                    GuideButtonLed.forBattery((byte) 1, (byte) 0).pattern());
        }

        /**
         * A pad about to die must still show something: an LED that went dark at critical would
         * read as the pad having already gone.
         */
        @Test
        @DisplayName("critical is not off")
        void criticalIsNotOff() {
            GuideButtonLed.State critical = GuideButtonLed.forBattery((byte) 1, (byte) 0);

            assertNotEquals(GuideButtonLed.PATTERN_OFF, critical.pattern());
            assertTrue(critical.intensity() > 0);
        }

        /** The steady steps dim as the battery drains; a brighter step for less charge is a bug. */
        @Test
        @DisplayName("intensity never rises as the level drops")
        void intensityNeverRisesAsTheLevelDrops() {
            int previous = Integer.MAX_VALUE;
            for (byte level = 3; level >= 1; level--) {
                int intensity = GuideButtonLed.forBattery((byte) 1, level).intensity();
                assertTrue(intensity <= previous,
                        "level " + level + " is brighter than the level above it");
                previous = intensity;
            }
        }

        /** Nothing may exceed what the protocol's intensity field can carry, whatever the input. */
        @Test
        @DisplayName("every state is within Table 41's range")
        void everyStateIsWithinTable41() {
            for (byte type = 0; type <= 3; type++) {
                for (byte level = 0; level <= 3; level++) {
                    int intensity = GuideButtonLed.forBattery(type, level).intensity();
                    assertTrue(intensity >= 0 && intensity <= GuideButtonLed.INTENSITY_MAX,
                            "type " + type + " level " + level + " gives " + intensity);
                }
            }
        }
    }

    @Nested
    @DisplayName("startState()")
    class StartState {

        /**
         * Pattern and intensity are separate fields (Table 42), so off has to be the off pattern:
         * an intensity of zero on the on pattern is a maximally dimmed on, and what a pad does
         * with that is not something the spec promises.
         */
        @Test
        @DisplayName("zero intensity selects the off pattern")
        void zeroIntensitySelectsTheOffPattern() {
            assertEquals(new GuideButtonLed.State(GuideButtonLed.PATTERN_OFF, 0),
                    new GuideButtonLed(0, false).startState());
        }

        @Test
        @DisplayName("any other intensity selects the on pattern")
        void nonZeroSelectsOn() {
            assertEquals(new GuideButtonLed.State(GuideButtonLed.PATTERN_ON, 0x14),
                    new GuideButtonLed(0x14, true).startState());
        }
    }

    @Nested
    @DisplayName("constants")
    class Constants {

        /**
         * These cross the JNI boundary as plain ints and are matched against {@code enum LedMode}
         * in {@code app/src/main/jni/xow_driver/controller/gip.h} by value. The native side
         * refuses a value outside that enum, so a drift would show as a refused command on
         * hardware; this pins the Java half so it shows here first.
         */
        @Test
        @DisplayName("patterns match enum LedMode in gip.h")
        void patternsMatchGipHeader() {
            assertEquals(0x00, GuideButtonLed.PATTERN_OFF);
            assertEquals(0x01, GuideButtonLed.PATTERN_ON);
            assertEquals(0x02, GuideButtonLed.PATTERN_BLINK_FAST);
            assertEquals(0x03, GuideButtonLed.PATTERN_BLINK_MED);
            assertEquals(0x04, GuideButtonLed.PATTERN_BLINK_SLOW);
            assertEquals(0x08, GuideButtonLed.PATTERN_FADE_SLOW);
            assertEquals(0x09, GuideButtonLed.PATTERN_FADE_FAST);
        }
    }
}
