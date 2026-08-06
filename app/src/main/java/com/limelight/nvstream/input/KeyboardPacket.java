package com.limelight.nvstream.input;

/**
 * Wire constants for keyboard events.
 *
 * <p>The modifier bits are sent alongside every keystroke rather than inferred from modifier key
 * events, so the host always knows the exact modifier state a key was pressed under.
 */
public class KeyboardPacket {
    public static final byte KEY_DOWN = 0x03;
    public static final byte KEY_UP = 0x04;

    public static final byte MODIFIER_SHIFT = 0x01;
    public static final byte MODIFIER_CTRL = 0x02;
    public static final byte MODIFIER_ALT = 0x04;
    public static final byte MODIFIER_META = 0x08;
}