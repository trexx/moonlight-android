package com.limelight.utils;

/**
 * Trims the text shown in the local echo above the soft keyboard.
 *
 * <p>The keyboard covers the bottom of the picture, which is where a game's chat box lives, so you
 * cannot see what you are entering. The alternative was moving or shrinking the video, which means
 * resizing the surface the decoder renders into for the sake of a text field. This shows the text
 * instead and leaves the video alone.
 *
 * <p>What it shows is the line {@link ImeTextModel} is driving the host towards - the same text
 * the field has, since every keystroke is worked out by comparing against that line. The two agree
 * by construction rather than by two paths happening to stay in step, which is what an earlier
 * attempt at this got wrong: it kept its own record of what had been typed, and a record can drift.
 *
 * <p>Pure logic with no Android dependencies, so it is reachable from a JVM test.
 */
public final class ImePreview {

    /**
     * How much to show. The tail is what matters - you are looking at where the cursor is - so a
     * longer line is trimmed from the front. Sized to fill a line across a TV without wrapping.
     */
    public static final int MAX_CHARS = 120;

    private ImePreview() {
    }

    /** Trims to {@link #MAX_CHARS}. */
    public static String build(CharSequence composing) {
        return build(composing, MAX_CHARS);
    }

    /**
     * @param composing the text the IME is still composing, which has not been sent anywhere yet
     * @param maxChars  how many characters the display can hold
     */
    static String build(CharSequence composing, int maxChars) {
        String text = composing == null ? "" : composing.toString();
        if (text.length() <= maxChars) {
            return text;
        }

        int start = text.length() - maxChars;

        // Trimming from the front can land between the halves of a surrogate pair, which would put
        // a lone low surrogate at the head of the line and draw as a replacement glyph.
        if (Character.isLowSurrogate(text.charAt(start))) {
            start++;
        }

        return text.substring(start);
    }
}
