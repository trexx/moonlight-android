package com.limelight.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the record of committed soft keyboard text that deletions are measured against.
 *
 * <p>The whole class exists for one discrepancy: an IME counts in UTF-16 code units and the host
 * deletes whole characters. Getting it wrong is quiet and specific - backspacing over an emoji
 * takes the character before it as well - so the surrogate cases carry the weight here.
 *
 * <p>The fallback matters as much as the arithmetic. When a deletion reaches further back than
 * this remembers, returning the raw code unit count restores exactly what the code did before the
 * buffer existed, which is the right answer to degrade to.
 */
class ImeTextBufferTest {

    /** Two UTF-16 code units, one character. */
    private static final String EMOJI = "😀";

    @Test
    @DisplayName("counts a plain character as one keystroke")
    void countsPlainCharacters() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("hello");

        assertEquals(1, buffer.removeBefore(1));
    }

    @Test
    @DisplayName("counts a surrogate pair as one keystroke, not two")
    void countsSurrogatePairAsOne() {
        // The bug this fixes: two code units used to mean two backspaces, so deleting an emoji
        // also deleted whatever preceded it.
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("hi" + EMOJI);

        assertEquals(1, buffer.removeBefore(2));
    }

    @Test
    @DisplayName("counts a mixed run in characters rather than code units")
    void countsMixedRun() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("a" + EMOJI + "b");

        // Four code units, three characters
        assertEquals(3, buffer.removeBefore(4));
    }

    @Test
    @DisplayName("consumes the text it reports, so a second deletion sees what is left")
    void consumesWhatItReports() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("ab" + EMOJI);

        assertEquals(1, buffer.removeBefore(2), "the emoji");
        assertEquals(2, buffer.length(), "\"ab\" is still there");
        assertEquals(2, buffer.removeBefore(2), "then both plain characters");
        assertEquals(0, buffer.length());
    }

    @Test
    @DisplayName("falls back to the code unit count when it does not remember that far back")
    void fallsBackWhenBufferIsShort() {
        // Reopening the keyboard over text the host already has. One keystroke per code unit is
        // the best guess available, and is what every deletion did before this class existed.
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("hi");

        assertEquals(5, buffer.removeBefore(5));
        assertEquals(0, buffer.length(), "and it stops pretending to know what is left");
    }

    @Test
    @DisplayName("falls back after the buffer has been cleared")
    void fallsBackAfterClear() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("hello");
        buffer.clear();

        assertEquals(3, buffer.removeBefore(3));
    }

    @ParameterizedTest(name = "removeBefore({0})")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("reports nothing to delete for a non-positive length")
    void reportsNothingForNonPositiveLength(int length) {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("hello");

        assertEquals(0, buffer.removeBefore(length));
        assertEquals(5, buffer.length(), "and leaves the buffer alone");
    }

    @Test
    @DisplayName("ignores empty and null commits")
    void ignoresEmptyCommits() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append(null);
        buffer.append("");

        assertEquals(0, buffer.length());
    }

    @Test
    @DisplayName("drops one plain character for a deletion that arrived as a key event")
    void removesLastPlainCharacter() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("hello");
        buffer.removeLastCharacter();

        assertEquals(4, buffer.length());
    }

    @Test
    @DisplayName("drops a surrogate pair whole, rather than splitting it")
    void removesLastSurrogatePairWhole() {
        // removeBefore(1) would take one code unit and leave an orphan behind, which is the
        // reason this method exists at all.
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("hi" + EMOJI);
        buffer.removeLastCharacter();

        assertEquals(2, buffer.length(), "\"hi\", with no half-emoji left over");
    }

    @Test
    @DisplayName("does nothing when there is nothing left to drop")
    void removesLastCharacterFromEmptyBuffer() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.removeLastCharacter();

        assertEquals(0, buffer.length());
    }

    @Test
    @DisplayName("still measures the rest correctly after dropping the last character")
    void measuresCorrectlyAfterRemovingLastCharacter() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("a" + EMOJI + "b");
        buffer.removeLastCharacter();

        // "a" and the emoji remain: three code units, two characters
        assertEquals(2, buffer.removeBefore(3));
    }

    @Test
    @DisplayName("keeps only the most recent text, so a long session cannot grow it")
    void boundsItsOwnGrowth() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        for (int i = 0; i < 100; i++) {
            buffer.append("0123456789");
        }

        assertEquals(ImeTextBuffer.CAPACITY, buffer.length());
    }

    @Test
    @DisplayName("still measures correctly against the text it kept after trimming")
    void measuresCorrectlyAfterTrimming() {
        ImeTextBuffer buffer = new ImeTextBuffer();
        buffer.append("x".repeat(ImeTextBuffer.CAPACITY));
        buffer.append(EMOJI);

        // The trim drops from the front, so the newest text - the only text a deletion can reach
        // - is intact
        assertEquals(1, buffer.removeBefore(2));
    }
}
