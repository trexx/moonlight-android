package com.limelight.binding.input;

import android.view.KeyEvent;

/**
 * Which keycodes {@link ControllerHandler#handleButtonUp} maps to a controller button.
 *
 * <p>This exists so the answer can be had <em>before</em> that method's minimum-hold sleep.
 * {@link ControllerHandler#isGameControllerDevice} says yes to any non-alphabetic keyboard, which
 * includes every TV remote, so without this check a remote's volume key sat on the UI thread for
 * up to 25 ms and then fell through to the keyboard path anyway. The list must match the
 * {@code switch} in {@code handleButtonUp} case for case; the test guards that.
 *
 * <p>Pure and JVM-testable: {@code KeyEvent}'s keycodes are compile-time constants, so nothing
 * here loads the framework class at runtime.
 */
final class ControllerButtonKeys {

    private ControllerButtonKeys() {}

    /**
     * @param keyCode    the keycode after remapping and face-button flipping
     * @param scanCode   the evdev scancode, consulted only for {@code KEYCODE_UNKNOWN}
     * @param hasPaddles whether the device reports Elite-style paddles on raw scancodes
     * @return true if {@code handleButtonUp} clears or updates some controller state for this key
     */
    static boolean isMappedOnRelease(int keyCode, int scanCode, boolean hasPaddles) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_MODE,
                 KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU,
                 KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_SELECT,
                 KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                 KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                 KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT,
                 KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
                 KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A,
                 KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                 KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                 KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                 KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_BUTTON_1,
                 KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> true;
            // Paddles arrive as KEYCODE_UNKNOWN with the BTN_TRIGGER_HAPPY5..8 scancodes
            case KeyEvent.KEYCODE_UNKNOWN -> hasPaddles && scanCode >= 0x2c4 && scanCode <= 0x2c7;
            default -> false;
        };
    }
}
