package com.limelight.utils;

/**
 * Maps Linux evdev scancodes to Windows virtual key codes.
 *
 * This is the fallback path for keys Android has no KeyEvent keycode for: see the
 * {@code default} branch of {@link com.limelight.binding.input.KeyboardTranslator#translate}.
 *
 * Sources:
 *   Linux codes  - https://github.com/torvalds/linux/blob/master/include/uapi/linux/input-event-codes.h
 *   Windows codes - https://learn.microsoft.com/en-us/windows/win32/inputdev/virtual-key-codes
 */
public class KeyMapper {
    /** Flat scancode-indexed lookup table; UNMAPPED for codes with no Windows equivalent. */
    private static final int UNMAPPED = -1;

    /** {linux scancode, windows VK} pairs, ascending by scancode. */
    private static final int[] MAPPINGS = {
              1, 0x1B,   // KEY_ESC                -> VK_ESCAPE
              2, 0x31,   // KEY_1                  -> VK_1
              3, 0x32,   // KEY_2                  -> VK_2
              4, 0x33,   // KEY_3                  -> VK_3
              5, 0x34,   // KEY_4                  -> VK_4
              6, 0x35,   // KEY_5                  -> VK_5
              7, 0x36,   // KEY_6                  -> VK_6
              8, 0x37,   // KEY_7                  -> VK_7
              9, 0x38,   // KEY_8                  -> VK_8
             10, 0x39,   // KEY_9                  -> VK_9
             11, 0x30,   // KEY_0                  -> VK_0
             12, 0xBD,   // KEY_MINUS              -> VK_OEM_MINUS
             13, 0xBB,   // KEY_EQUAL              -> VK_OEM_PLUS
             14, 0x08,   // KEY_BACKSPACE          -> VK_BACK
             15, 0x09,   // KEY_TAB                -> VK_TAB
             16, 0x51,   // KEY_Q                  -> VK_Q
             17, 0x57,   // KEY_W                  -> VK_W
             18, 0x45,   // KEY_E                  -> VK_E
             19, 0x52,   // KEY_R                  -> VK_R
             20, 0x54,   // KEY_T                  -> VK_T
             21, 0x59,   // KEY_Y                  -> VK_Y
             22, 0x55,   // KEY_U                  -> VK_U
             23, 0x49,   // KEY_I                  -> VK_I
             24, 0x4F,   // KEY_O                  -> VK_O
             25, 0x50,   // KEY_P                  -> VK_P
             26, 0xDB,   // KEY_LEFTBRACE          -> VK_OEM_4
             27, 0xDD,   // KEY_RIGHTBRACE         -> VK_OEM_6
             28, 0x0D,   // KEY_ENTER              -> VK_RETURN
             29, 0xA2,   // KEY_LEFTCTRL           -> VK_LCONTROL
             30, 0x41,   // KEY_A                  -> VK_A
             31, 0x53,   // KEY_S                  -> VK_S
             32, 0x44,   // KEY_D                  -> VK_D
             33, 0x46,   // KEY_F                  -> VK_F
             34, 0x47,   // KEY_G                  -> VK_G
             35, 0x48,   // KEY_H                  -> VK_H
             36, 0x4A,   // KEY_J                  -> VK_J
             37, 0x4B,   // KEY_K                  -> VK_K
             38, 0x4C,   // KEY_L                  -> VK_L
             39, 0xBA,   // KEY_SEMICOLON          -> VK_OEM_1
             40, 0xDE,   // KEY_APOSTROPHE         -> VK_OEM_7
             41, 0xC0,   // KEY_GRAVE              -> VK_OEM_3
             42, 0xA0,   // KEY_LEFTSHIFT          -> VK_LSHIFT
             43, 0xDC,   // KEY_BACKSLASH          -> VK_OEM_5
             44, 0x5A,   // KEY_Z                  -> VK_Z
             45, 0x58,   // KEY_X                  -> VK_X
             46, 0x43,   // KEY_C                  -> VK_C
             47, 0x56,   // KEY_V                  -> VK_V
             48, 0x42,   // KEY_B                  -> VK_B
             49, 0x4E,   // KEY_N                  -> VK_N
             50, 0x4D,   // KEY_M                  -> VK_M
             51, 0xBC,   // KEY_COMMA              -> VK_OEM_COMMA
             52, 0xBE,   // KEY_DOT                -> VK_OEM_PERIOD
             53, 0xBF,   // KEY_SLASH              -> VK_OEM_2
             54, 0xA1,   // KEY_RIGHTSHIFT         -> VK_RSHIFT
             55, 0x6A,   // KEY_KPASTERISK         -> VK_MULTIPLY
             56, 0xA4,   // KEY_LEFTALT            -> VK_LMENU
             57, 0x20,   // KEY_SPACE              -> VK_SPACE
             58, 0x14,   // KEY_CAPSLOCK           -> VK_CAPITAL
             59, 0x70,   // KEY_F1                 -> VK_F1
             60, 0x71,   // KEY_F2                 -> VK_F2
             61, 0x72,   // KEY_F3                 -> VK_F3
             62, 0x73,   // KEY_F4                 -> VK_F4
             63, 0x74,   // KEY_F5                 -> VK_F5
             64, 0x75,   // KEY_F6                 -> VK_F6
             65, 0x76,   // KEY_F7                 -> VK_F7
             66, 0x77,   // KEY_F8                 -> VK_F8
             67, 0x78,   // KEY_F9                 -> VK_F9
             68, 0x79,   // KEY_F10                -> VK_F10
             69, 0x90,   // KEY_NUMLOCK            -> VK_NUMLOCK
             70, 0x91,   // KEY_SCROLLLOCK         -> VK_SCROLL
             71, 0x67,   // KEY_KP7                -> VK_NUMPAD7
             72, 0x68,   // KEY_KP8                -> VK_NUMPAD8
             73, 0x69,   // KEY_KP9                -> VK_NUMPAD9
             74, 0x6D,   // KEY_KPMINUS            -> VK_SUBTRACT
             75, 0x64,   // KEY_KP4                -> VK_NUMPAD4
             76, 0x65,   // KEY_KP5                -> VK_NUMPAD5
             77, 0x66,   // KEY_KP6                -> VK_NUMPAD6
             78, 0x6B,   // KEY_KPPLUS             -> VK_ADD
             79, 0x61,   // KEY_KP1                -> VK_NUMPAD1
             80, 0x62,   // KEY_KP2                -> VK_NUMPAD2
             81, 0x63,   // KEY_KP3                -> VK_NUMPAD3
             82, 0x60,   // KEY_KP0                -> VK_NUMPAD0
             83, 0x6E,   // KEY_KPDOT              -> VK_DECIMAL
             86, 0xE2,   // KEY_102ND              -> VK_OEM_102
             87, 0x7A,   // KEY_F11                -> VK_F11
             88, 0x7B,   // KEY_F12                -> VK_F12
             97, 0xA3,   // KEY_RIGHTCTRL          -> VK_RCONTROL
             99, 0x2A,   // KEY_SYSRQ              -> VK_PRINT
            100, 0xA5,   // KEY_RIGHTALT           -> VK_RMENU
            102, 0x24,   // KEY_HOME               -> VK_HOME
            103, 0x26,   // KEY_UP                 -> VK_UP
            104, 0x21,   // KEY_PAGEUP             -> VK_PRIOR
            105, 0x25,   // KEY_LEFT               -> VK_LEFT
            106, 0x27,   // KEY_RIGHT              -> VK_RIGHT
            107, 0x23,   // KEY_END                -> VK_END
            108, 0x28,   // KEY_DOWN               -> VK_DOWN
            109, 0x22,   // KEY_PAGEDOWN           -> VK_NEXT
            110, 0x2D,   // KEY_INSERT             -> VK_INSERT
            111, 0x2E,   // KEY_DELETE             -> VK_DELETE
            119, 0x13,   // KEY_PAUSE              -> VK_PAUSE
            125, 0x5B,   // KEY_LEFTMETA           -> VK_LWIN
            126, 0x5C,   // KEY_RIGHTMETA          -> VK_RWIN
            127, 0xE5,   // KEY_COMPOSE            -> VK_PROCESSKEY
            183, 0x7C,   // KEY_F13                -> VK_F13
            184, 0x7D,   // KEY_F14                -> VK_F14
            185, 0x7E,   // KEY_F15                -> VK_F15
            186, 0x7F,   // KEY_F16                -> VK_F16
            187, 0x80,   // KEY_F17                -> VK_F17
            188, 0x81,   // KEY_F18                -> VK_F18
            189, 0x82,   // KEY_F19                -> VK_F19
            190, 0x83,   // KEY_F20                -> VK_F20
            191, 0x84,   // KEY_F21                -> VK_F21
            192, 0x85,   // KEY_F22                -> VK_F22
            193, 0x86,   // KEY_F23                -> VK_F23
            194, 0x87,   // KEY_F24                -> VK_F24
    };

    private static final int[] LINUX_TO_WINDOWS = buildTable();

    private static int[] buildTable() {
        int[] table = new int[195];
        java.util.Arrays.fill(table, UNMAPPED);
        for (int i = 0; i < MAPPINGS.length; i += 2) {
            table[MAPPINGS[i]] = MAPPINGS[i + 1];
        }
        return table;
    }

    /**
     * @param linuxKeyCode a Linux evdev scancode
     * @return the equivalent Windows virtual key code, or -1 if there is no mapping
     */
    public static int getWindowsKeyCode(int linuxKeyCode) {
        if (linuxKeyCode >= 0 && linuxKeyCode < LINUX_TO_WINDOWS.length) {
            return LINUX_TO_WINDOWS[linuxKeyCode];
        }
        return UNMAPPED;
    }
}
