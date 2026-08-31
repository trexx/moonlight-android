package com.limelight.utils;

/**
 * What the soft keyboard means the host's text field to contain, and what has to be sent to get it
 * there.
 *
 * <p>The host has no text field we can write to - only a keyboard we can press - so every change an
 * IME makes has to be expressed as keystrokes. Translating each callback into keystrokes as it
 * arrives is the obvious way to do that and it is wrong, because an IME does not describe changes,
 * it describes states. Gboard implements backspace over a finished word by deleting the whole word
 * and re-composing it a character shorter: taken literally that is five backspaces and four
 * letters retyped for one keypress, and holding the key buries the send queue under work it can
 * never drain. That is where duplicated and truncated text came from.
 *
 * <p>So this tracks two strings - what the host has, and what the IME means it to have - and
 * {@link #reconcile()} works out the shortest way from one to the other. The same backspace becomes
 * one backspace, because that is all that actually changed.
 *
 * <p><b>Composing text counts.</b> A word part way through being typed is as much a statement of
 * what the field should contain as a committed one, so it is included and the host tracks the
 * keyboard live. That is only safe because of the comparison: replaying callbacks would have typed
 * {@code "h"}, {@code "he"}, {@code "hel"} one prefix at a time, whereas comparing sends the single
 * character that changed. It is also what makes backspace immediate - the deletion and the shorter
 * composition that replaces it are one difference of one character, applied at once, rather than a
 * word withheld until something commits.
 *
 * <p>Bounded, because a stream can outlast a great deal of typing. Trimming happens only when the
 * two strings agree, so the same characters leave both and the next comparison still lines up.
 *
 * <p>Pure logic with no Android dependencies, so it is reachable from a JVM test.
 */
public final class ImeTextModel {

    /**
     * How much typed text to keep. Deletions arrive a word at a time at most - an autocorrect
     * replacing what was just typed is the longest case - so this is far more than any single edit
     * can reach back through.
     */
    static final int CAPACITY = 256;

    /**
     * The keystrokes that carry the host from what it has to what it should have.
     *
     * @param backspaces    characters to remove before the cursor, counted as characters rather
     *                      than UTF-16 units - one backspace deletes an emoji whole
     * @param forwardDeletes characters to remove after it
     * @param insert        text to type once the deletions are done
     */
    public record Edit(int backspaces, int forwardDeletes, String insert) {

        public boolean isEmpty() {
            return backspaces == 0 && forwardDeletes == 0 && insert.isEmpty();
        }
    }

    private final StringBuilder sent = new StringBuilder();
    private final StringBuilder committed = new StringBuilder();
    private final StringBuilder composing = new StringBuilder();
    private final StringBuilder intended = new StringBuilder();
    private int forwardDeletes;

    /** Records text the IME has committed, which supersedes any composition it finishes. */
    public void commit(CharSequence text) {
        composing.setLength(0);
        if (text != null) {
            committed.append(text);
        }
    }

    /** Records the word being composed, which counts towards the intended text like any other. */
    public void composing(CharSequence text) {
        composing.setLength(0);
        if (text != null) {
            composing.append(text);
        }
    }

    /** Accepts the composition as final, for an IME that ends a word without committing it. */
    public void finishComposing() {
        committed.append(composing);
        composing.setLength(0);
    }

    /**
     * Records a deletion around the cursor, in the IME's own UTF-16 units.
     *
     * <p>Taken off the end of the composition first, then off the committed text behind it, which
     * is the order the characters sit in. Nothing is worked out here: the conversion to keystrokes
     * happens in {@link #reconcile()}, once the IME has finished saying what it wants.
     */
    public void delete(int beforeLength, int afterLength) {
        int remaining = beforeLength;

        int fromComposing = Math.min(remaining, composing.length());
        composing.setLength(composing.length() - fromComposing);
        remaining -= fromComposing;

        if (remaining > 0) {
            committed.setLength(Math.max(committed.length() - remaining, 0));
        }

        if (afterLength > 0) {
            forwardDeletes += afterLength;
        }
    }

    /**
     * Records that the host has lost a character without being asked to by this model.
     *
     * <p>Some IMEs delete with a {@code KEYCODE_DEL} key event rather than calling
     * {@code deleteSurroundingText}, and that key reaches the host on its own. Both sides have to
     * forget it together: dropping it from the intent alone would leave the comparison believing
     * the host still holds a character it does not, and the next reconcile would type it back.
     */
    public void hostDeletedCharacter() {
        dropLastCharacter(composing.length() != 0 ? composing : committed);
        dropLastCharacter(sent);
    }

    /** Removes one whole character, so a surrogate pair is never left half present. */
    private static void dropLastCharacter(StringBuilder text) {
        if (text.length() != 0) {
            text.setLength(text.offsetByCodePoints(text.length(), -1));
        }
    }

    /** @return the text the field should contain, committed and composing alike */
    public CharSequence text() {
        intended.setLength(0);
        return intended.append(committed).append(composing);
    }

    /**
     * Works out what to send, and assumes it has been.
     *
     * @return the keystrokes to send, or null when the host already has what it should
     */
    public Edit reconcile() {
        Edit edit = between(sent, text(), forwardDeletes);
        forwardDeletes = 0;

        sent.setLength(0);
        sent.append(intended);
        trim();

        return edit.isEmpty() ? null : edit;
    }

    /** Forgets everything, for a keyboard that has gone away and a host we can no longer account for. */
    public void reset() {
        sent.setLength(0);
        committed.setLength(0);
        composing.setLength(0);
        forwardDeletes = 0;
    }

    /** @return the shortest edit turning {@code sent} into {@code desired} */
    public static Edit between(CharSequence sent, CharSequence desired, int forwardDeletes) {
        int prefix = commonPrefixLength(sent, desired);

        return new Edit(Character.codePointCount(sent, prefix, sent.length()),
                forwardDeletes,
                desired.subSequence(prefix, desired.length()).toString());
    }

    /** @return how many UTF-16 units the two share from the start, never splitting a code point */
    private static int commonPrefixLength(CharSequence a, CharSequence b) {
        int max = Math.min(a.length(), b.length());

        int i = 0;
        while (i < max) {
            int codePoint = Character.codePointAt(a, i);
            int width = Character.charCount(codePoint);

            // A pair that runs off the end of the shorter string is not shared, whatever its first
            // half says.
            if (i + width > max || codePoint != Character.codePointAt(b, i)) {
                break;
            }

            i += width;
        }

        return i;
    }

    /** Drops the oldest text once there is more than a single edit could ever reach back through. */
    private void trim() {
        if (sent.length() <= CAPACITY) {
            return;
        }

        int drop = sent.length() - CAPACITY;
        if (Character.isLowSurrogate(sent.charAt(drop))) {
            drop++;
        }

        // Safe only because this runs with the host and the intent in agreement: the same
        // characters leave both, so the next comparison still starts from the same place. The
        // composition sits at the end, so only the committed text ahead of it is ever trimmed.
        sent.delete(0, drop);
        committed.delete(0, Math.min(drop, committed.length()));
    }

    /** @return how much the host is believed to hold, in UTF-16 units */
    int sentLength() {
        return sent.length();
    }
}
