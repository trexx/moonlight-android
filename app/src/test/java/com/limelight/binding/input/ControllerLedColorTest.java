package com.limelight.binding.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the light bar colour packing.
 *
 * <p>The failure this guards against is silent and looks like a hardware fault rather than a bug:
 * the light bar showing white, or a colour nothing asked for, while every value on the wire is
 * correct. moonlight-common-c hands the components over as signed Java bytes, so every component
 * from 0x80 up arrives negative, and an unmasked {@code r << 16} sign-extends across the top of the
 * word. Half the colour space is on the wrong side of that boundary, which is why the cases below
 * lean on components at and above 0x80 rather than on the tidy primaries.
 */
class ControllerLedColorTest {

    @Nested
    @DisplayName("components that fit in a positive byte")
    class PositiveComponents {

        @ParameterizedTest(name = "({0}, {1}, {2}) -> {3}")
        @CsvSource({
                // r,    g,    b,    expected ARGB
                "0x00, 0x00, 0x00, 0xFF000000",
                "0x7F, 0x00, 0x00, 0xFF7F0000",
                "0x00, 0x7F, 0x00, 0xFF007F00",
                "0x00, 0x00, 0x7F, 0xFF00007F",
                "0x12, 0x34, 0x56, 0xFF123456",
        })
        void packsIntoTheRightBytes(int r, int g, int b, long expected) {
            assertEquals((int) expected, ControllerLedColor.toArgb((byte) r, (byte) g, (byte) b));
        }
    }

    @Nested
    @DisplayName("components above 0x7F, which arrive as negative bytes")
    class NegativeComponents {

        /**
         * Full-brightness red is the case that broke: {@code (byte) 0xFF} is -1, so {@code r << 16}
         * is 0xFFFF0000 and an unmasked OR would light green and blue as well. Red has to stay red.
         */
        @Test
        @DisplayName("full red does not bleed into the other channels")
        void fullRedStaysRed() {
            assertEquals(0xFFFF0000, ControllerLedColor.toArgb((byte) 0xFF, (byte) 0x00, (byte) 0x00));
        }

        /**
         * Green shifts by 8 rather than 16, so it sign-extends over red as well as its own byte.
         * A separate case because the two shifts fail differently.
         */
        @Test
        @DisplayName("full green does not bleed into red")
        void fullGreenStaysGreen() {
            assertEquals(0xFF00FF00, ControllerLedColor.toArgb((byte) 0x00, (byte) 0xFF, (byte) 0x00));
        }

        @ParameterizedTest(name = "({0}, {1}, {2}) -> {3}")
        @CsvSource({
                // r,    g,    b,    expected ARGB
                "0x00, 0x00, 0xFF, 0xFF0000FF",
                "0x80, 0x00, 0x00, 0xFF800000",
                "0x00, 0x80, 0x00, 0xFF008000",
                "0x00, 0x00, 0x80, 0xFF000080",
                "0xFF, 0xFF, 0xFF, 0xFFFFFFFF",
                "0x80, 0x80, 0x80, 0xFF808080",
                "0xDE, 0xAD, 0xBE, 0xFFDEADBE",
        })
        void packsIntoTheRightBytes(int r, int g, int b, long expected) {
            assertEquals((int) expected, ControllerLedColor.toArgb((byte) r, (byte) g, (byte) b));
        }
    }

    /**
     * The host has no way to ask for a transparent light bar, and {@code LightState} treats alpha
     * as brightness — so leaving it unset would read as "off" for every colour.
     */
    @Test
    @DisplayName("alpha is always opaque")
    void alphaIsAlwaysOpaque() {
        assertEquals(0xFF000000, ControllerLedColor.toArgb((byte) 0, (byte) 0, (byte) 0) & 0xFF000000);
        assertEquals(0xFF000000, ControllerLedColor.toArgb((byte) 0xFF, (byte) 0xFF, (byte) 0xFF) & 0xFF000000);
        assertEquals(0xFF000000, ControllerLedColor.toArgb((byte) 0x80, (byte) 0x40, (byte) 0x20) & 0xFF000000);
    }
}
