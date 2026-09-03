package com.limelight.binding.input;

import android.annotation.TargetApi;
import android.hardware.input.InputManager;
import android.os.Build;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.limelight.utils.KeyMapper;

import java.util.Arrays;

/**
 * Class to translate a Android key code into the codes GFE is expecting
 *
 * <p>The host expects Windows virtual key codes from a QWERTY layout, so translation happens in
 * three stages: a non-QWERTY layout is first normalised to QWERTY using the device's own key
 * character map, the Android keycode is then mapped to its Windows equivalent, and keys Android
 * has no keycode for fall back to mapping the raw Linux scancode via {@link KeyMapper}.
 *
 * <p>Layout mappings are cached per input device and kept current by implementing
 * {@link InputManager.InputDeviceListener}, since a keyboard's layout can change while attached.
 *
 * @author Diego Waxemberg
 * @author Cameron Gutman
 */
public class KeyboardTranslator implements InputManager.InputDeviceListener {
    
    /**
     * GFE's prefix for every key code
     */
    private static final short KEY_PREFIX = (short) 0x80;
    
    // Sentinel for "this key has no Windows equivalent". Negative because every real VK code is
    // positive, so one check below the switch covers both this and a scancode KeyMapper rejected.
    private static final int NOT_TRANSLATED = -1;

    public static final int VK_0 = 48;
    public static final int VK_9 = 57;
    public static final int VK_A = 65;
    public static final int VK_D = 68;
    public static final int VK_G = 71;
    public static final int VK_V = 86;
    public static final int VK_Z = 90;
    public static final int VK_NUMPAD0 = 96;
    public static final int VK_BACK_SLASH = 92;
    public static final int VK_CAPS_LOCK = 20;
    public static final int VK_CLEAR = 12;
    public static final int VK_COMMA = 44;
    public static final int VK_BACK_SPACE = 8;
    public static final int VK_EQUALS = 61;
    public static final int VK_ESCAPE = 27;
    public static final int VK_RETURN = 13;
    public static final int VK_F1 = 112;
    public static final int VK_F4 = 115;
    public static final int VK_F11 = 122;
    public static final int VK_F13 = 0x7C;
    public static final int VK_END = 35;
    public static final int VK_HOME = 36;
    public static final int VK_NUM_LOCK = 144;
    public static final int VK_PAGE_UP = 33;
    public static final int VK_PAGE_DOWN = 34;
    public static final int VK_PLUS = 521;
    public static final int VK_CLOSE_BRACKET = 93;
    public static final int VK_SCROLL_LOCK = 145;
    public static final int VK_SEMICOLON = 59;
    public static final int VK_SLASH = 47;
    public static final int VK_SPACE = 32;
    public static final int VK_PRINTSCREEN = 154;
    public static final int VK_TAB = 9;
    public static final int VK_LEFT = 37;
    public static final int VK_RIGHT = 39;
    public static final int VK_UP = 38;
    public static final int VK_DOWN = 40;
    public static final int VK_BACK_QUOTE = 192;
    public static final int VK_QUOTE = 222;
    public static final int VK_PAUSE = 19;
    public static final int VK_LWIN = 91;
    public static final int VK_LSHIFT = 160;
    public static final int VK_LCONTROL = 162;
    public static final int VK_LMENU = 164;

    /**
     * One keyboard's layout, as a lookup from the keycodes it produces to the QWERTY keycodes
     * they correspond to.
     *
     * <p>Built by asking the device which key produces each QWERTY character and inverting that,
     * so an AZERTY keyboard's Q key resolves to {@code KEYCODE_A} — which is what the host, which
     * assumes QWERTY, needs to receive.
     */
    private static class KeyboardMapping {
        private final InputDevice device;
        private final int[] deviceKeyCodeToQwertyKeyCode;

        /** Builds the reverse map by asking the device which key produces each QWERTY character. */
        @TargetApi(33)
        public KeyboardMapping(InputDevice device) {
            int maxKeyCode = KeyEvent.getMaxKeyCode();

            this.device = device;
            this.deviceKeyCodeToQwertyKeyCode = new int[maxKeyCode + 1];

            // Any unmatched keycodes are treated as unknown
            Arrays.fill(deviceKeyCodeToQwertyKeyCode, KeyEvent.KEYCODE_UNKNOWN);

            for (int i = 0; i <= maxKeyCode; i++) {
                int deviceKeyCode = device.getKeyCodeForKeyLocation(i);
                if (deviceKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    deviceKeyCodeToQwertyKeyCode[deviceKeyCode] = i;
                }
            }
        }

        /**
         * @return the QWERTY keycode this device's key corresponds to, or
         *         {@link KeyEvent#KEYCODE_UNKNOWN} if the layout doesn't move this key
         */
        @TargetApi(33)
        public int getQwertyKeyCodeForDeviceKeyCode(int deviceKeyCode) {
            if (deviceKeyCode > KeyEvent.getMaxKeyCode()) {
                return KeyEvent.KEYCODE_UNKNOWN;
            }

            return deviceKeyCodeToQwertyKeyCode[deviceKeyCode];
        }
    }

    private final SparseArray<KeyboardMapping> keyboardMappings = new SparseArray<>();

    /** Caches layout mappings for every alphabetic keyboard currently attached. */
    public KeyboardTranslator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (int deviceId : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device != null && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    keyboardMappings.set(deviceId, new KeyboardMapping(device));
                }
            }
        }
    }

    /**
     * @return true if this device's layout maps the keycode onto a different QWERTY key, meaning
     *         the raw keycode would be wrong to send as-is
     */
    public boolean hasNormalizedMapping(int keycode, int deviceId) {
        if (deviceId >= 0) {
            KeyboardMapping mapping = keyboardMappings.get(deviceId);
            if (mapping != null) {
                // Try to map this device-specific keycode onto a QWERTY layout.
                // GFE assumes incoming keycodes are from a QWERTY keyboard.
                int qwertyKeyCode = mapping.getQwertyKeyCodeForDeviceKeyCode(keycode);
                if (qwertyKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Translates the given keycode and returns the GFE keycode
     * @param keycode the code to be translated
     * @param deviceId InputDevice.getId() or -1 if unknown
     * @return a GFE keycode for the given keycode
     */
    public short translate(int keycode, int deviceId) {
        return translate(keycode, deviceId, -1);
    }

    /**
     * Translates an Android keycode, falling back to the hardware scancode for keys
     * that Android has no keycode for (many international and media keys). Without the
     * fallback those keystrokes are silently dropped.
     *
     * @param keycode the code to be translated
     * @param deviceId InputDevice.getId() or -1 if unknown
     * @param scancode KeyEvent.getScanCode() or -1 if unknown
     * @return a GFE keycode for the given keycode
     */
    public short translate(int keycode, int deviceId, int scancode) {
        int translated;

        // If a device ID was provided, look up the keyboard mapping
        if (deviceId >= 0) {
            KeyboardMapping mapping = keyboardMappings.get(deviceId);
            if (mapping != null) {
                // Try to map this device-specific keycode onto a QWERTY layout.
                // GFE assumes incoming keycodes are from a QWERTY keyboard.
                int qwertyKeyCode = mapping.getQwertyKeyCodeForDeviceKeyCode(keycode);
                if (qwertyKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    keycode = qwertyKeyCode;
                }
            }
        }
        
        // This is a poor man's mapping between Android key codes
        // and Windows VK_* codes. For all defined VK_ codes, see:
        // https://msdn.microsoft.com/en-us/library/windows/desktop/dd375731(v=vs.85).aspx
        if (keycode >= KeyEvent.KEYCODE_0 &&
            keycode <= KeyEvent.KEYCODE_9) {
            translated = (keycode - KeyEvent.KEYCODE_0) + VK_0;
        }
        else if (keycode >= KeyEvent.KEYCODE_A &&
                 keycode <= KeyEvent.KEYCODE_Z) {
            translated = (keycode - KeyEvent.KEYCODE_A) + VK_A;
        }
        else if (keycode >= KeyEvent.KEYCODE_NUMPAD_0 &&
                 keycode <= KeyEvent.KEYCODE_NUMPAD_9) {
            translated = (keycode - KeyEvent.KEYCODE_NUMPAD_0) + VK_NUMPAD0;
        }
        else if (keycode >= KeyEvent.KEYCODE_F1 &&
                 keycode <= KeyEvent.KEYCODE_F12) {
            translated = (keycode - KeyEvent.KEYCODE_F1) + VK_F1;
        }
        // KEYCODE_F13 through KEYCODE_F24 are API 36, so no supported box can deliver them yet -
        // the Shield is API 30 and the Homatics API 34. Carried for the same reason as the
        // producer-throttling call in Game: a keyboard with these keys is ordinary hardware, and
        // the mapping should already be right when a box updates or a newer device joins the set.
        // No SDK_INT guard, because none is possible or needed - the constants inline at compile
        // time, and a platform that does not define a keycode never sends it.
        else if (keycode >= KeyEvent.KEYCODE_F13 &&
                 keycode <= KeyEvent.KEYCODE_F24) {
            translated = (keycode - KeyEvent.KEYCODE_F13) + VK_F13;
        }
        else {
            translated = switch (keycode) {
                case KeyEvent.KEYCODE_ALT_LEFT -> VK_LMENU;
                case KeyEvent.KEYCODE_ALT_RIGHT -> 0xA5;
                case KeyEvent.KEYCODE_BACKSLASH -> 0xdc;
                case KeyEvent.KEYCODE_CAPS_LOCK -> VK_CAPS_LOCK;
                case KeyEvent.KEYCODE_CLEAR -> VK_CLEAR;
                case KeyEvent.KEYCODE_COMMA -> 0xbc;
                case KeyEvent.KEYCODE_CTRL_LEFT -> VK_LCONTROL;
                case KeyEvent.KEYCODE_CTRL_RIGHT -> 0xA3;
                case KeyEvent.KEYCODE_DEL -> VK_BACK_SPACE;
                case KeyEvent.KEYCODE_ENTER -> 0x0d;
                case KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> 0xbb;
                case KeyEvent.KEYCODE_ESCAPE -> VK_ESCAPE;
                case KeyEvent.KEYCODE_FORWARD_DEL -> 0x2e;
                case KeyEvent.KEYCODE_INSERT -> 0x2d;
                case KeyEvent.KEYCODE_LEFT_BRACKET -> 0xdb;
                case KeyEvent.KEYCODE_META_LEFT -> VK_LWIN;
                case KeyEvent.KEYCODE_META_RIGHT -> 0x5c;
                case KeyEvent.KEYCODE_MENU -> 0x5d;
                case KeyEvent.KEYCODE_MINUS -> 0xbd;
                case KeyEvent.KEYCODE_MOVE_END -> VK_END;
                case KeyEvent.KEYCODE_MOVE_HOME -> VK_HOME;
                case KeyEvent.KEYCODE_NUM_LOCK -> VK_NUM_LOCK;
                case KeyEvent.KEYCODE_PAGE_DOWN -> VK_PAGE_DOWN;
                case KeyEvent.KEYCODE_PAGE_UP -> VK_PAGE_UP;
                case KeyEvent.KEYCODE_PERIOD -> 0xbe;
                case KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xdd;
                case KeyEvent.KEYCODE_SCROLL_LOCK -> VK_SCROLL_LOCK;
                case KeyEvent.KEYCODE_SEMICOLON -> 0xba;
                case KeyEvent.KEYCODE_SHIFT_LEFT -> VK_LSHIFT;
                case KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xA1;
                case KeyEvent.KEYCODE_SLASH -> 0xbf;
                case KeyEvent.KEYCODE_SPACE -> VK_SPACE;
                // Android defines this as SysRq/PrntScrn
                case KeyEvent.KEYCODE_SYSRQ -> VK_PRINTSCREEN;
                // The dedicated print and screenshot keys, split out of SysRq by newer platforms:
                // KEYCODE_PRINT is API 36 and KEYCODE_SCREENSHOT API 35, so neither box reaches
                // them yet. VK_PRINT and VK_SNAPSHOT are what the host expects for the two.
                case KeyEvent.KEYCODE_PRINT -> 0x2a;
                case KeyEvent.KEYCODE_SCREENSHOT -> 0x2c;
                case KeyEvent.KEYCODE_TAB -> VK_TAB;
                case KeyEvent.KEYCODE_DPAD_LEFT -> VK_LEFT;
                case KeyEvent.KEYCODE_DPAD_RIGHT -> VK_RIGHT;
                case KeyEvent.KEYCODE_DPAD_UP -> VK_UP;
                case KeyEvent.KEYCODE_DPAD_DOWN -> VK_DOWN;
                case KeyEvent.KEYCODE_GRAVE -> VK_BACK_QUOTE;
                case KeyEvent.KEYCODE_APOSTROPHE -> 0xde;
                case KeyEvent.KEYCODE_BREAK -> VK_PAUSE;
                case KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x6F;
                case KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x6A;
                case KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x6D;
                case KeyEvent.KEYCODE_NUMPAD_ADD -> 0x6B;
                case KeyEvent.KEYCODE_NUMPAD_DOT -> 0x6E;
                // Android has no keycode for this key. Fall back to translating the
                // hardware scancode, which is a Linux evdev code, into a Windows VK.
                // NOT_TRANSLATED rather than an early return: a switch expression has
                // to produce a value, so the caller checks for it just below.
                default -> scancode >= 0 ? KeyMapper.getWindowsKeyCode(scancode) : NOT_TRANSLATED;
            };

            if (translated < 0) {
                return 0;
            }
        }
        
        return (short) ((KEY_PREFIX << 8) | translated);
    }

    /**
     * Applies the on-the-wire key prefix to a raw Windows virtual key code, producing the same
     * encoding {@link #translate} emits. Callers that already know the VK they want to send
     * (rather than deriving it from a {@link KeyEvent}) should use this instead of passing the
     * bare VK, so every keyboard packet we send is encoded identically.
     */
    public static short toWireKeycode(int windowsVirtualKey) {
        return (short) ((KEY_PREFIX << 8) | windowsVirtualKey);
    }

    /** {@inheritDoc} Caches the layout mapping for a newly attached keyboard. */
    @Override
    public void onInputDeviceAdded(int index) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InputDevice device = InputDevice.getDevice(index);
            if (device != null && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                keyboardMappings.put(index, new KeyboardMapping(device));
            }
        }
    }

    /** {@inheritDoc} Drops the cached layout mapping. */
    @Override
    public void onInputDeviceRemoved(int index) {
        keyboardMappings.remove(index);
    }

    /** {@inheritDoc} Rebuilds the cached mapping, since the device's layout may have changed. */
    @Override
    public void onInputDeviceChanged(int index) {
        keyboardMappings.remove(index);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InputDevice device = InputDevice.getDevice(index);
            if (device != null && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                keyboardMappings.set(index, new KeyboardMapping(device));
            }
        }
    }
}
