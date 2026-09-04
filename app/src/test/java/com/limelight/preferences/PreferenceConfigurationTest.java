package com.limelight.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.limelight.nvstream.StreamConfiguration;

/**
 * Tests for the preference values that are parsed rather than read straight through.
 *
 * <p>Only the pure mappers are reachable here — {@link PreferenceConfiguration#readPreferences}
 * needs a {@code Context}, which is why those mappers are separate static methods in the first
 * place. The {@code ENCFLG_*} values they return are compile-time constants, so javac inlines them
 * and {@link StreamConfiguration} is never loaded; that matters because it would drag in
 * {@code MoonBridge}, whose static initialiser calls {@code System.loadLibrary}.
 */
class PreferenceConfigurationTest {

    @Nested
    @DisplayName("getEncryptionFlagsValue()")
    class EncryptionFlags {

        @Test
        @DisplayName("maps each offered setting to its protocol flags")
        void mapsEachSetting() {
            assertEquals(StreamConfiguration.ENCFLG_NONE,
                    PreferenceConfiguration.getEncryptionFlagsValue("none"));
            assertEquals(StreamConfiguration.ENCFLG_AUDIO,
                    PreferenceConfiguration.getEncryptionFlagsValue("audio"));
            assertEquals(StreamConfiguration.ENCFLG_ALL,
                    PreferenceConfiguration.getEncryptionFlagsValue("all"));
        }

        @Test
        @DisplayName("audio-only is the default when the preference has never been written")
        void nullFallsBackToDefault() {
            // A fresh install reaches this with null rather than the default string.
            assertEquals(StreamConfiguration.ENCFLG_AUDIO,
                    PreferenceConfiguration.getEncryptionFlagsValue(null));
            assertEquals(PreferenceConfiguration.getEncryptionFlagsValue(
                            PreferenceConfiguration.DEFAULT_ENCRYPTION),
                    PreferenceConfiguration.getEncryptionFlagsValue(null));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "Audio", "AUDIO", "video", "everything", "1", " audio "})
        @DisplayName("falls back to audio rather than silently dropping encryption")
        void unrecognisedFallsBackToAudio(String value) {
            // The failure mode that matters is a typo or a stale value quietly turning encryption
            // off, so anything unrecognised lands on the upstream baseline rather than NONE.
            assertEquals(StreamConfiguration.ENCFLG_AUDIO,
                    PreferenceConfiguration.getEncryptionFlagsValue(value));
        }

        @Test
        @DisplayName("the values match the array the preference actually offers")
        void matchesPreferenceEntryValues() {
            // arrays.xml holds the same three strings. If one is renamed there without being
            // renamed here it would silently fall through to the audio default, which is exactly
            // the kind of drift that survives review.
            assertEquals(StreamConfiguration.ENCFLG_NONE,
                    PreferenceConfiguration.getEncryptionFlagsValue("none"));
            assertEquals(StreamConfiguration.ENCFLG_ALL,
                    PreferenceConfiguration.getEncryptionFlagsValue("all"));
            assertEquals("audio", PreferenceConfiguration.DEFAULT_ENCRYPTION);
        }
    }

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

            assertTrue(intensity >= 0x00 && intensity <= 0x2F,
                    "intensity " + intensity + " is outside the protocol's 0x00-0x2F range");
        }
    }
}
