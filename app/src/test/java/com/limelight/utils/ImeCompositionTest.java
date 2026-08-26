package com.limelight.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the composing-word tracker.
 *
 * <p>Every test here is really the same test: the word reaches the host once. Sending a preview
 * types a prefix of it, and delivering a composition that a commit already covered types it twice
 * - which is the fault this class was written for, where backspacing out of a word retyped it.
 */
class ImeCompositionTest {

    @Test
    @DisplayName("sends nothing while the word is still being composed")
    void sendsNothingWhileComposing() {
        ImeComposition composition = new ImeComposition();

        // How an IME spells "hey": the whole word so far, once per keystroke
        composition.composing("h");
        composition.composing("he");
        composition.composing("hey");

        assertTrue(composition.isPending(), "and it is still holding it");
    }

    @Test
    @DisplayName("sends the finished word when the IME finalises it without committing")
    void sendsTheFinishedWord() {
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");

        assertEquals("hey", composition.finish());
        assertFalse(composition.isPending());
    }

    @Test
    @DisplayName("sends only the last preview, not every one of them")
    void sendsOnlyTheLastPreview() {
        ImeComposition composition = new ImeComposition();
        composition.composing("h");
        composition.composing("he");
        composition.composing("hey");

        assertEquals("hey", composition.finish());
    }

    @Test
    @DisplayName("sends a shortened composition, which is how backspace inside a word arrives")
    void sendsAShortenedComposition() {
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");
        composition.composing("he");

        assertEquals("he", composition.finish());
    }

    @Test
    @DisplayName("sends nothing on a second finish")
    void sendsNothingOnASecondFinish() {
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");
        composition.finish();

        assertNull(composition.finish());
    }

    @Test
    @DisplayName("sends nothing when finishing without a composition")
    void sendsNothingWithoutAComposition() {
        assertNull(new ImeComposition().finish());
    }

    @Test
    @DisplayName("sends the committed text and drops the composition it supersedes")
    void commitSupersedesTheComposition() {
        // The whole point: the commit IS the finished word, so finishing afterwards - which is
        // what an IME does next - must not send it a second time.
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");

        assertEquals("hey", composition.commit("hey"));
        assertNull(composition.finish(), "the word has already gone");
    }

    @Test
    @DisplayName("sends a commit that differs from what was composed, once")
    void commitMayDifferFromTheComposition() {
        // Autocorrect: the composition read "hte", the commit is what it was corrected to
        ImeComposition composition = new ImeComposition();
        composition.composing("hte");

        assertEquals("the", composition.commit("the"));
        assertNull(composition.finish());
    }

    @Test
    @DisplayName("sends a commit made with nothing composed")
    void commitWithoutAComposition() {
        assertEquals(" ", new ImeComposition().commit(" "));
    }

    @Test
    @DisplayName("sends nothing for an empty or absent commit")
    void sendsNothingForAnEmptyCommit() {
        ImeComposition composition = new ImeComposition();

        assertNull(composition.commit(null));
        assertNull(composition.commit(""));
    }

    @Test
    @DisplayName("drops the composition when the commit is empty, rather than holding it")
    void emptyCommitStillSupersedes() {
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");

        assertNull(composition.commit(""));
        assertNull(composition.finish(), "an empty commit still ended the composition");
    }

    @Test
    @DisplayName("treats a cleared composition as no composition")
    void clearedCompositionIsNoComposition() {
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");
        composition.composing("");

        assertFalse(composition.isPending());
        assertNull(composition.finish());
    }

    @Test
    @DisplayName("forgets an abandoned composition, so a later finish cannot deliver it")
    void resetForgetsTheComposition() {
        // The keyboard was dismissed mid-word. That word was never finalised, so it is not text
        // the user asked to send.
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");
        composition.reset();

        assertNull(composition.finish());
    }

    @Test
    @DisplayName("starts a new word cleanly after the previous one went")
    void composesAgainAfterDelivery() {
        ImeComposition composition = new ImeComposition();
        composition.composing("hey");
        assertEquals("hey", composition.finish());

        composition.composing("there");
        assertEquals("there", composition.finish());
    }
}
