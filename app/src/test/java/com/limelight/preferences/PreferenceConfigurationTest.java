package com.limelight.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for the preference-value mappings that are reachable without a {@code Context}.
 *
 * <p>Only the static string-to-value helpers are covered. {@code readPreferences} needs a real
 * {@code SharedPreferences} and is out of reach of a JVM test.
 */
class PreferenceConfigurationTest {

    @Nested
    @DisplayName("guide button LED brightness")
    class GuideButtonLed {

        /**
         * The values are the protocol's, not ours: MS-GIPUSB 3.1.5.5.7 Table 41 defines the LED
         * command's intensity byte as 0 to 47. Pinning them here is the point of the test — a
         * preset that silently changed value would otherwise only be visible on hardware.
         */
        @ParameterizedTest(name = "\"{0}\" maps to {1}")
        @CsvSource({
                "off,    0x00",
                "dim,    0x0A",
                "normal, 0x14",
                "bright, 0x2F",
        })
        @DisplayName("each preset maps to its protocol intensity")
        void presetsMapToIntensity(String stored, int expected) {
            assertEquals(expected, PreferenceConfiguration.getGuideButtonLedValue(stored));
        }

        /**
         * A preference the user has never touched reads back as null, and a value written by a
         * future build might be anything. Both have to land on the intensity the driver used
         * before this was configurable, rather than leaving the LED off or at full brightness.
         */
        @Test
        @DisplayName("null falls back to the normal intensity")
        void nullFallsBackToNormal() {
            assertEquals(0x14, PreferenceConfiguration.getGuideButtonLedValue(null));
        }

        @Test
        @DisplayName("an unrecognised value falls back to the normal intensity")
        void unrecognisedFallsBackToNormal() {
            assertEquals(0x14, PreferenceConfiguration.getGuideButtonLedValue("chartreuse"));
        }

        /** Nothing may exceed what the protocol's intensity field can carry. */
        @ParameterizedTest
        @CsvSource({"off", "dim", "normal", "bright"})
        @DisplayName("no preset exceeds the protocol maximum of 0x2F")
        void presetsStayWithinProtocolRange(String stored) {
            int intensity = PreferenceConfiguration.getGuideButtonLedValue(stored);

            org.junit.jupiter.api.Assertions.assertTrue(
                    intensity >= 0x00 && intensity <= 0x2F,
                    "intensity " + intensity + " is outside the protocol's 0x00-0x2F range");
        }
    }
}
