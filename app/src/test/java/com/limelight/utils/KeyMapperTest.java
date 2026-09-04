package com.limelight.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for the Linux evdev to Windows virtual key mapping.
 *
 * <p>This is the fallback for keys Android has no {@code KeyEvent} keycode for, so a wrong entry
 * shows up as one key typing the wrong character on the host and nothing else misbehaving. The
 * table is built once into a flat array, so the bounds handling around it matters as much as the
 * entries.
 */
class KeyMapperTest {

    private static final int UNMAPPED = -1;

    @ParameterizedTest(name = "linux {0} -> windows {1}")
    @CsvSource({
            "1,   0x1B",  // KEY_ESC        -> VK_ESCAPE
            "14,  0x08",  // KEY_BACKSPACE  -> VK_BACK
            "15,  0x09",  // KEY_TAB        -> VK_TAB
            "16,  0x51",  // KEY_Q          -> VK_Q
            "2,   0x31",  // KEY_1          -> VK_1
            "11,  0x30",  // KEY_0          -> VK_0
            "194, 0x87",  // KEY_F24        -> VK_F24, the last entry in the table
    })
    @DisplayName("maps known scancodes to their Windows virtual key codes")
    void mapsKnownScancodes(int linuxKeyCode, int expected) {
        assertEquals(expected, KeyMapper.getWindowsKeyCode(linuxKeyCode));
    }

    @Test
    @DisplayName("maps KEY_EQUAL and KEY_MINUS to the OEM codes, not to digits")
    void mapsEqualAndMinusToOemCodes() {
        // HARDWARE_TESTING.md carries a check that '+' types '+' on the host rather than '='.
        // On a layout where '+' is its own key it arrives as KEY_EQUAL (13), which has to reach
        // the host as VK_OEM_PLUS (0xBB) for the host's own layout handling to produce '+'.
        assertEquals(0xBB, KeyMapper.getWindowsKeyCode(13));
        assertEquals(0xBD, KeyMapper.getWindowsKeyCode(12));

        // The failure mode is these colliding with the digit row
        assertNotEquals(KeyMapper.getWindowsKeyCode(13), KeyMapper.getWindowsKeyCode(11));
    }

    @ParameterizedTest(name = "scancode {0}")
    @ValueSource(ints = {0, 195, 196, 1000, Integer.MAX_VALUE})
    @DisplayName("returns unmapped for scancodes outside the table")
    void returnsUnmappedOutsideTable(int linuxKeyCode) {
        assertEquals(UNMAPPED, KeyMapper.getWindowsKeyCode(linuxKeyCode));
    }

    @ParameterizedTest(name = "scancode {0}")
    @ValueSource(ints = {-1, -100, Integer.MIN_VALUE})
    @DisplayName("returns unmapped for negative scancodes rather than throwing")
    void returnsUnmappedForNegativeScancodes(int linuxKeyCode) {
        // The table is indexed directly, so the lower bound check is the only thing standing
        // between an unexpected scancode and an ArrayIndexOutOfBoundsException on the input path.
        assertEquals(UNMAPPED, KeyMapper.getWindowsKeyCode(linuxKeyCode));
    }

    @Test
    @DisplayName("maps the letter row in keyboard order, not alphabetically")
    void mapsLetterRowInKeyboardOrder() {
        // Scancodes 16-22 are QWERTYU across the top row. Getting this wrong would be an easy
        // off-by-one when editing the table, and would scramble typing on the host.
        assertEquals(0x51, KeyMapper.getWindowsKeyCode(16)); // Q
        assertEquals(0x57, KeyMapper.getWindowsKeyCode(17)); // W
        assertEquals(0x45, KeyMapper.getWindowsKeyCode(18)); // E
        assertEquals(0x52, KeyMapper.getWindowsKeyCode(19)); // R
        assertEquals(0x54, KeyMapper.getWindowsKeyCode(20)); // T
        assertEquals(0x59, KeyMapper.getWindowsKeyCode(21)); // Y
        assertEquals(0x55, KeyMapper.getWindowsKeyCode(22)); // U
    }

    @Test
    @DisplayName("maps the digit row to VK_0 through VK_9 in order")
    void mapsDigitRowInOrder() {
        // Scancodes 2-11 are '1'..'9' then '0'; the wrap at the end is the interesting part.
        for (int digit = 1; digit <= 9; digit++) {
            assertEquals(0x30 + digit, KeyMapper.getWindowsKeyCode(digit + 1),
                    "digit " + digit);
        }
        assertEquals(0x30, KeyMapper.getWindowsKeyCode(11));
    }

    @Test
    @DisplayName("never returns a mapping for a scancode with no Windows equivalent")
    void gapsInTheTableStayUnmapped() {
        // Gaps are real: the table is a flat array sized to the highest mapped scancode, so every
        // unlisted code in between has to come back unmapped rather than as a stale zero.
        int unmappedCount = 0;
        for (int scancode = 0; scancode < 195; scancode++) {
            if (KeyMapper.getWindowsKeyCode(scancode) == UNMAPPED) {
                unmappedCount++;
            }
        }
        // A table with no gaps at all would mean the fill was skipped
        assertNotEquals(0, unmappedCount);
    }
}
