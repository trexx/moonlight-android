package com.limelight.utils;

/**
 * Tracks the word a soft keyboard is still composing, so it reaches the host exactly once.
 *
 * <p>An IME does not type a word a character at a time. It calls {@code setComposingText} with the
 * whole word so far on every keystroke, then finalises it either by committing it or by calling
 * {@code finishComposingText} and leaving it to the editor. Only the final form should reach the
 * host: sending each preview would type {@code "h"}, {@code "he"}, {@code "hel"} in turn.
 *
 * <p>{@link android.view.inputmethod.BaseInputConnection} used to absorb the previews into a
 * scratch {@code Editable} on our behalf, but it cannot be left to: outside full-editor mode it
 * flushes that buffer back into the view as key events the instant composition ends, and the
 * stream view forwards those to the host as a second copy of the word. That is what made backspace
 * appear to type rather than delete - ending the composition is exactly what an IME does when you
 * backspace out of one. So the previews stop here instead, and this decides what to send.
 *
 * <p>The load-bearing rule is that a composition is delivered once or not at all. A commit
 * supersedes whatever was being composed, because the committed text <em>is</em> the finished form
 * of it; finishing after that must send nothing, or the word arrives twice.
 *
 * <p>Pure logic with no Android dependencies, so it is reachable from a JVM test.
 */
public final class ImeComposition {

    private final StringBuilder pending = new StringBuilder();

    /** Records a preview of the word being composed. Nothing is sent while composition continues. */
    public void composing(CharSequence text) {
        pending.setLength(0);
        if (text != null) {
            pending.append(text);
        }
    }

    /**
     * Ends the composition, the IME having declared it final.
     *
     * @return the text to send the host, or null if there is no composition outstanding - either
     *         none was started, or a commit already delivered it
     */
    public String finish() {
        if (pending.length() == 0) {
            return null;
        }

        String text = pending.toString();
        pending.setLength(0);
        return text;
    }

    /**
     * Records that the IME has committed text, which replaces any composition in progress.
     *
     * @return the text to send the host, or null if there is nothing to send
     */
    public String commit(CharSequence text) {
        pending.setLength(0);
        return text == null || text.length() == 0 ? null : text.toString();
    }

    /** Abandons any composition in progress, so it can never be delivered later. */
    public void reset() {
        pending.setLength(0);
    }

    /** @return whether a composition is outstanding */
    boolean isPending() {
        return pending.length() != 0;
    }
}
