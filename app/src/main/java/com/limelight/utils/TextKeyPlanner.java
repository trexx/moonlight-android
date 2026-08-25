package com.limelight.utils;

import com.limelight.nvstream.input.KeyboardPacket;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns soft keyboard input into the sequence of events to send the host.
 *
 * <p>The reason this exists at all: text committed by an IME used to leave entirely as
 * {@code LiSendUtf8TextEvent}, which the host injects as Unicode character input — on Sunshine for
 * Windows, {@code SendInput} with {@code KEYEVENTF_UNICODE}. Chromium-based UIs such as Steam Big
 * Picture consume that happily, but games read the keyboard through DirectInput, Raw Input or
 * {@code GetAsyncKeyState}, all of which are scancode-based and never see a synthetic Unicode
 * event. Typing worked everywhere except the one place it was wanted.
 *
 * <p>So every character that has a key on a US QWERTY keyboard now leaves as a real keystroke, and
 * the UTF-8 path survives only as the fallback for characters that have no key at all: CJK, emoji,
 * and anything needing a dead key. A plan interleaves the two in source order, because a single
 * commit can contain both.
 *
 * <p>Pure logic with no Android dependencies, so it is reachable from a JVM test — see
 * {@link KeyMapper} for the same shape. {@link KeyboardPacket} is safe to depend on for the same
 * reason: it is wire constants and nothing else.
 */
public final class TextKeyPlanner {

    /** One event to send the host. Either a keystroke or a run of text with no keys to send. */
    public sealed interface Step permits Key, Text {}

    /**
     * A single key transition.
     *
     * @param windowsKeyCode the Windows virtual key code, before the wire prefix is applied
     * @param pressed        true for a key down, false for the matching key up
     * @param modifiers      the {@link KeyboardPacket} modifier bits in force for this key
     */
    public record Key(int windowsKeyCode, boolean pressed, byte modifiers) implements Step {}

    /** A run of characters with no key equivalent, already split to fit the control stream. */
    public record Text(String text) implements Step {}

    /**
     * Payload limit for one text event. The control stream has no flow control of its own, so
     * larger commits are split and paced by the caller.
     */
    public static final int UTF8_CHUNK_SIZE = 512;

    // Windows virtual key codes. Duplicated from KeyboardTranslator rather than imported: that
    // class pulls in android.hardware.input.InputManager and cannot load in a JVM test.
    private static final int VK_BACK_SPACE = 0x08;
    private static final int VK_TAB = 0x09;
    private static final int VK_RETURN = 0x0D;
    private static final int VK_SPACE = 0x20;
    private static final int VK_DELETE = 0x2E;
    private static final int VK_LSHIFT = 0xA0;

    /** No key on a US QWERTY layout produces this character. */
    private static final int UNMAPPED = -1;

    /**
     * Marks a table entry as needing shift. Set above the 16 bits any virtual key code occupies,
     * so the key and the shift state share one lookup.
     */
    private static final int SHIFT = 0x10000;

    /**
     * {character, virtual key} pairs for the keys whose character is not derivable by arithmetic.
     *
     * <p>These are the US QWERTY OEM keys, whose virtual key codes bear no relation to the
     * character they produce — the host applies its own layout to them. They match what
     * {@code KeyboardTranslator.translate} emits for the equivalent {@code KeyEvent}, and both
     * must stay in step: a character typed on the soft keyboard should reach the host as the same
     * key a physical keyboard would have sent.
     */
    private static final int[] PUNCTUATION = {
            ' ',  VK_SPACE,
            '\t', VK_TAB,
            // Enter in a game's chat box is the case that failed silently before this existed
            '\n', VK_RETURN,

            ';',  0xBA,          // VK_OEM_1
            ':',  0xBA | SHIFT,
            '=',  0xBB,          // VK_OEM_PLUS
            '+',  0xBB | SHIFT,
            ',',  0xBC,          // VK_OEM_COMMA
            '<',  0xBC | SHIFT,
            '-',  0xBD,          // VK_OEM_MINUS
            '_',  0xBD | SHIFT,
            '.',  0xBE,          // VK_OEM_PERIOD
            '>',  0xBE | SHIFT,
            '/',  0xBF,          // VK_OEM_2
            '?',  0xBF | SHIFT,
            '`',  0xC0,          // VK_OEM_3
            '~',  0xC0 | SHIFT,
            '[',  0xDB,          // VK_OEM_4
            '{',  0xDB | SHIFT,
            '\\', 0xDC,          // VK_OEM_5
            '|',  0xDC | SHIFT,
            ']',  0xDD,          // VK_OEM_6
            '}',  0xDD | SHIFT,
            '\'', 0xDE,          // VK_OEM_7
            '"',  0xDE | SHIFT,

            // The shifted digit row, in the order the keys sit: shift+1 is '!', shift+0 is ')'
            '!',  '1' | SHIFT,
            '@',  '2' | SHIFT,
            '#',  '3' | SHIFT,
            '$',  '4' | SHIFT,
            '%',  '5' | SHIFT,
            '^',  '6' | SHIFT,
            '&',  '7' | SHIFT,
            '*',  '8' | SHIFT,
            '(',  '9' | SHIFT,
            ')',  '0' | SHIFT,
    };

    /** Flat character-indexed lookup over ASCII; UNMAPPED for characters with no US QWERTY key. */
    private static final int[] CHAR_TO_KEY = buildTable();

    private static int[] buildTable() {
        int[] table = new int[0x80];
        Arrays.fill(table, UNMAPPED);

        // Letters and digits are mechanical: their virtual key codes are their own uppercase
        // ASCII values, which is why KeyboardTranslator can do the same with range arithmetic.
        for (char c = 'a'; c <= 'z'; c++) {
            table[c] = Character.toUpperCase(c);
            table[Character.toUpperCase(c)] = Character.toUpperCase(c) | SHIFT;
        }
        for (char c = '0'; c <= '9'; c++) {
            table[c] = c;
        }

        for (int i = 0; i < PUNCTUATION.length; i += 2) {
            table[PUNCTUATION[i]] = PUNCTUATION[i + 1];
        }

        return table;
    }

    private TextKeyPlanner() {
    }

    /** Plans committed IME text, splitting any fallback text at {@link #UTF8_CHUNK_SIZE}. */
    public static List<Step> planText(String text) {
        return planText(text, UTF8_CHUNK_SIZE);
    }

    /**
     * Plans committed IME text.
     *
     * <p>Shift is pressed as a real key rather than only set in the modifier byte, for the same
     * reason the whole class exists: a game asking {@code GetAsyncKeyState(VK_SHIFT)} sees the key,
     * not the byte. Consecutive shifted characters hold it down across the run, so {@code "HELLO"}
     * costs one press rather than five.
     *
     * <p>Every plan ends with each key released, so a caller that stops draining part-way through
     * can leave a key stuck only if it stops between a down and its up — never at the end.
     *
     * @param utf8ChunkSize payload limit for one fallback text event
     * @return the events to send, in the order the characters appeared
     */
    static List<Step> planText(String text, int utf8ChunkSize) {
        List<Step> steps = new ArrayList<>();
        StringBuilder fallback = new StringBuilder();
        boolean shiftHeld = false;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);

            int entry = codePoint < CHAR_TO_KEY.length ? CHAR_TO_KEY[codePoint] : UNMAPPED;
            if (entry == UNMAPPED) {
                // Release shift before handing anything to the text path. The host applies the
                // modifier byte to its own injection, so text typed while we still held shift
                // would arrive capitalised.
                if (shiftHeld) {
                    steps.add(shift(false));
                    shiftHeld = false;
                }
                fallback.appendCodePoint(codePoint);
                continue;
            }

            // Anything buffered for the text path has to go out before this keystroke, or a
            // mixed commit like "café ok" arrives on the host out of order.
            flushFallback(steps, fallback, utf8ChunkSize);

            boolean needsShift = (entry & SHIFT) != 0;
            if (needsShift != shiftHeld) {
                steps.add(shift(needsShift));
                shiftHeld = needsShift;
            }

            int windowsKeyCode = entry & 0xFFFF;
            byte modifiers = needsShift ? KeyboardPacket.MODIFIER_SHIFT : 0;
            steps.add(new Key(windowsKeyCode, true, modifiers));
            steps.add(new Key(windowsKeyCode, false, modifiers));
        }

        if (shiftHeld) {
            steps.add(shift(false));
        }
        flushFallback(steps, fallback, utf8ChunkSize);

        return steps;
    }

    /**
     * Plans a deletion around the cursor.
     *
     * <p>Both counts are whole characters, not UTF-16 code units: the caller resolves that, because
     * only it can see the text being deleted. Getting it wrong deletes one character too many
     * after an emoji.
     *
     * @param backspaces    characters to remove before the cursor
     * @param forwardDeletes characters to remove after it
     */
    public static List<Step> planDeletion(int backspaces, int forwardDeletes) {
        List<Step> steps = new ArrayList<>();

        // The host has no notion of our IME's buffer, so a deletion can only be approximated with
        // the keys a person would have pressed to make it.
        for (int i = 0; i < backspaces; i++) {
            steps.add(new Key(VK_BACK_SPACE, true, (byte) 0));
            steps.add(new Key(VK_BACK_SPACE, false, (byte) 0));
        }
        for (int i = 0; i < forwardDeletes; i++) {
            steps.add(new Key(VK_DELETE, true, (byte) 0));
            steps.add(new Key(VK_DELETE, false, (byte) 0));
        }

        return steps;
    }

    /**
     * @return a left shift transition, carrying no modifier bits of its own — the same convention
     *         {@code GameMenu.sendKeys} uses, where a modifier key is sent before the modifier it
     *         establishes is applied to anything
     */
    private static Step shift(boolean pressed) {
        return new Key(VK_LSHIFT, pressed, (byte) 0);
    }

    /** Drains buffered fallback characters into text events, never splitting a UTF-8 sequence. */
    private static void flushFallback(List<Step> steps, StringBuilder fallback, int utf8ChunkSize) {
        // Not isEmpty(): CharSequence gained that at API 35 and minSdk here is 30, so lint
        // rejects it. StringBuilder has no isEmpty() of its own to fall back on.
        if (fallback.length() == 0) {
            return;
        }

        byte[] utf8 = fallback.toString().getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < utf8.length) {
            int end = Math.min(offset + utf8ChunkSize, utf8.length);

            // A chunk that ended mid-sequence would arrive at the host as mojibake. Continuation
            // bytes are 10xxxxxx, so walk back off them to the start of the code point.
            while (end < utf8.length && (utf8[end] & 0xC0) == 0x80) {
                end--;
            }
            if (end <= offset) {
                break;
            }

            steps.add(new Text(new String(utf8, offset, end - offset, StandardCharsets.UTF_8)));
            offset = end;
        }

        fallback.setLength(0);
    }
}
