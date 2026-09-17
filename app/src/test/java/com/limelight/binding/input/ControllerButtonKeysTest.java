package com.limelight.binding.input;

import android.view.KeyEvent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the keycode gate in front of {@code handleButtonUp}'s minimum-hold sleep.
 *
 * <p>A key wrongly reported as unmapped loses its 25 ms hold and a game polling once per frame
 * can miss the press; a key wrongly reported as mapped stalls the UI thread for a remote's
 * volume button. Both are silent, so the set is pinned here.
 */
class ControllerButtonKeysTest {

    @ParameterizedTest(name = "keycode {0} is a controller button")
    @ValueSource(ints = {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_BUTTON_1,
    })
    void controllerButtonsAreMapped(int keyCode) {
        assertTrue(ControllerButtonKeys.isMappedOnRelease(keyCode, 0, false));
    }

    @ParameterizedTest(name = "keycode {0} is not a controller button")
    @ValueSource(ints = {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_Z, KeyEvent.KEYCODE_BUTTON_2,
    })
    void remoteAndKeyboardKeysAreNotMapped(int keyCode) {
        assertFalse(ControllerButtonKeys.isMappedOnRelease(keyCode, 0, false));
    }

    @Test
    void paddlesAreMappedOnlyByScancodeAndOnlyWithPaddles() {
        for (int scanCode = 0x2c4; scanCode <= 0x2c7; scanCode++) {
            assertTrue(ControllerButtonKeys.isMappedOnRelease(KeyEvent.KEYCODE_UNKNOWN, scanCode, true));
            assertFalse(ControllerButtonKeys.isMappedOnRelease(KeyEvent.KEYCODE_UNKNOWN, scanCode, false));
        }
        assertFalse(ControllerButtonKeys.isMappedOnRelease(KeyEvent.KEYCODE_UNKNOWN, 0x2c3, true));
        assertFalse(ControllerButtonKeys.isMappedOnRelease(KeyEvent.KEYCODE_UNKNOWN, 0x2c8, true));
    }
}
