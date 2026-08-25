package com.limelight.utils;

/**
 * A running record of what the soft keyboard has committed, kept so deletions can be measured.
 *
 * <p>An IME asks for a deletion in UTF-16 code units, but the host deletes whole characters: one
 * backspace removes one character whether it was one code unit or two. Sending one backspace per
 * code unit therefore eats an extra character every time an emoji is deleted. Nothing in the
 * deletion callback says which characters are being removed, so the only way to tell the two
 * counts apart is to remember what was sent.
 *
 * <p>{@link android.view.inputmethod.BaseInputConnection} cannot supply this. Constructed outside
 * full-editor mode, as {@code StreamView} does, its {@code commitText} synthesises key events back
 * into the view and then clears its own buffer, so there is nothing left to read back - and reading
 * it would mean letting it re-inject every character a second time.
 *
 * <p>Bounded, because a stream can outlast a great deal of typing and none of the older text can
 * ever be deleted through this path anyway.
 *
 * <p>Pure logic with no Android dependencies, so it is reachable from a JVM test.
 */
public final class ImeTextBuffer {

    /**
     * How much typed text to keep. Deletions arrive a word at a time at most - an autocorrect
     * replacing what was just typed is the longest case - so this is already far more than any
     * single deletion can reach back through.
     */
    static final int CAPACITY = 256;

    private final StringBuilder text = new StringBuilder();

    /** Records text the IME has committed. */
    public void append(CharSequence committed) {
        if (committed == null || committed.length() == 0) {
            return;
        }

        text.append(committed);

        if (text.length() > CAPACITY) {
            text.delete(0, text.length() - CAPACITY);
        }
    }

    /**
     * Removes the text a deletion covers and reports what it costs in keystrokes.
     *
     * @param utf16Length the IME's own count, in UTF-16 code units
     * @return the number of characters to delete on the host. Falls back to {@code utf16Length}
     *         when the deletion reaches further back than this buffer remembers - the keyboard was
     *         reopened over text the host already had, and one keystroke per code unit is the best
     *         guess left. That is what this did for every deletion before the buffer existed.
     */
    public int removeBefore(int utf16Length) {
        if (utf16Length <= 0) {
            return 0;
        }

        if (utf16Length > text.length()) {
            text.setLength(0);
            return utf16Length;
        }

        int start = text.length() - utf16Length;
        int codePoints = text.codePointCount(start, text.length());
        text.setLength(start);
        return codePoints;
    }

    /** Forgets everything typed so far. */
    public void clear() {
        text.setLength(0);
    }

    /** @return the number of UTF-16 code units currently remembered */
    int length() {
        return text.length();
    }
}
