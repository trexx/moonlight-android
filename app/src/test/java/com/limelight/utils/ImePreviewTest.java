package com.limelight.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the text shown in the local echo above the soft keyboard.
 *
 * <p>The line comes from {@link ImeTextModel}, which already knows what the host holds, so there is
 * no bookkeeping here to get wrong: what is left is trimming a long line down to the end the user
 * is watching, without cutting a surrogate pair in half on the way.
 */
class ImePreviewTest {

    /** Two UTF-16 code units, one character. */
    private static final String EMOJI = "😀";

    @Test
    @DisplayName("shows nothing when the line is empty")
    void showsNothingWhenTheLineIsEmpty() {
        assertTrue(ImePreview.build("").isEmpty());
    }

    @Test
    @DisplayName("treats absent text as empty rather than failing")
    void toleratesNull() {
        assertTrue(ImePreview.build(null).isEmpty());
    }

    @Test
    @DisplayName("shows the line being typed")
    void showsTheLine() {
        assertEquals("hello there", ImePreview.build("hello there"));
    }

    @Test
    @DisplayName("shows a dictated phrase whole")
    void showsADictatedPhrase() {
        assertEquals("send help please", ImePreview.build("send help please"));
    }

    @Test
    @DisplayName("keeps the tail when the line is longer than the display")
    void keepsTheTail() {
        // The cursor is at the end, so that is the part worth seeing.
        assertEquals("6789", ImePreview.build("0123456789", 4));
    }

    @Test
    @DisplayName("leaves a line that already fits alone")
    void leavesAFittingLineAlone() {
        assertEquals("0123", ImePreview.build("0123", 4));
    }

    @Test
    @DisplayName("never leaves half a surrogate pair at the head of the line")
    void neverSplitsASurrogatePair() {
        // The trim lands between the two halves of the emoji; taking it as written would put a
        // lone low surrogate first, which draws as a replacement glyph.
        String preview = ImePreview.build("X" + EMOJI + "abcd", 5);

        assertEquals("abcd", preview);
        assertFalse(Character.isLowSurrogate(preview.charAt(0)));
    }

    @Test
    @DisplayName("keeps a surrogate pair that fits")
    void keepsAWholeSurrogatePair() {
        assertEquals("ab" + EMOJI, ImePreview.build("ab" + EMOJI, 4));
    }

    @Test
    @DisplayName("trims to the documented length by default")
    void trimsToTheDefaultLength() {
        assertEquals(ImePreview.MAX_CHARS,
                ImePreview.build("x".repeat(ImePreview.MAX_CHARS + 50)).length());
    }
}
