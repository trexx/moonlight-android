package com.limelight.utils;

import com.limelight.utils.ImeTextModel.Edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the model of what the host's text field should contain.
 *
 * <p>The case that matters most is the one this class was written for. Gboard implements backspace
 * over a finished word by deleting the whole word and re-composing it a character shorter; sending
 * that literally costs five backspaces and four retyped letters for one keypress, and holding the
 * key buries the send queue. Reconciled, it costs one backspace. {@link Reconciling} pins that.
 */
class ImeTextModelTest {

    /** Two UTF-16 code units, one character. */
    private static final String EMOJI = "😀";

    @Nested
    @DisplayName("between()")
    class Between {

        @Test
        @DisplayName("sends nothing when the host already has what it should")
        void sendsNothingWhenAlreadyRight() {
            assertEquals(new Edit(0, 0, ""), ImeTextModel.between("hello", "hello", 0));
        }

        @Test
        @DisplayName("types the difference when text is added")
        void typesTheDifference() {
            assertEquals(new Edit(0, 0, " there"), ImeTextModel.between("hello", "hello there", 0));
        }

        @Test
        @DisplayName("deletes only what the two stop sharing")
        void deletesOnlyTheDivergence() {
            // The whole reason for the class: "hello" to "hell" is one backspace, not five and
            // four letters back.
            assertEquals(new Edit(1, 0, ""), ImeTextModel.between("hello", "hell", 0));
        }

        @Test
        @DisplayName("deletes back to the divergence and retypes from there")
        void deletesThenRetypes() {
            assertEquals(new Edit(2, 0, "p"), ImeTextModel.between("help", "hep", 0));
        }

        @Test
        @DisplayName("counts a surrogate pair as one backspace")
        void countsASurrogatePairAsOne() {
            // One press of backspace removes an emoji whole, so asking for two would eat the
            // character before it as well.
            assertEquals(new Edit(1, 0, ""), ImeTextModel.between("hi" + EMOJI, "hi", 0));
        }

        @Test
        @DisplayName("does not treat half a shared surrogate pair as shared")
        void doesNotShareHalfAPair() {
            // "a" plus a high surrogate would be a common prefix by code unit, and splitting there
            // would leave the host holding half a character.
            Edit edit = ImeTextModel.between("a" + EMOJI, "a", 0);

            assertEquals(1, edit.backspaces());
            assertEquals("", edit.insert());
        }

        @Test
        @DisplayName("replaces the lot when nothing is shared")
        void replacesWhenNothingIsShared() {
            assertEquals(new Edit(3, 0, "xyz"), ImeTextModel.between("abc", "xyz", 0));
        }

        @Test
        @DisplayName("carries forward deletes through untouched")
        void carriesForwardDeletes() {
            assertEquals(new Edit(0, 2, ""), ImeTextModel.between("abc", "abc", 2));
        }
    }

    @Nested
    @DisplayName("reconcile()")
    class Reconciling {

        @Test
        @DisplayName("types committed text")
        void typesCommittedText() {
            ImeTextModel model = new ImeTextModel();
            model.commit("hello");

            assertEquals(new Edit(0, 0, "hello"), model.reconcile());
        }

        @Test
        @DisplayName("sends nothing twice for the same text")
        void sendsNothingTwice() {
            ImeTextModel model = new ImeTextModel();
            model.commit("hello");
            model.reconcile();

            assertNull(model.reconcile());
        }

        @Test
        @DisplayName("holds while a word is still being composed")
        void holdsWhileComposing() {
            // Sending previews would type the word out one prefix at a time: h, he, hel.
            ImeTextModel model = new ImeTextModel();
            model.composing("hel");

            assertNull(model.reconcile());
        }

        @Test
        @DisplayName("collapses Gboard's delete-and-recompose to one backspace")
        void collapsesDeleteAndRecompose() {
            // The fault this class exists for. The host has "hello"; one press of backspace
            // arrives as a deletion of the whole word followed by a composition of "hell".
            ImeTextModel model = new ImeTextModel();
            model.commit("hello");
            model.reconcile();

            model.delete(5, 0);
            model.composing("hell");
            assertNull(model.reconcile(), "the deletion is held, because something is replacing it");

            model.commit("hell");
            assertEquals(new Edit(1, 0, ""), model.reconcile(), "one backspace, not five and four letters");
        }

        @Test
        @DisplayName("still deletes when nothing replaces what was removed")
        void deletesWhenNothingReplacesIt() {
            ImeTextModel model = new ImeTextModel();
            model.commit("hello");
            model.reconcile();

            model.delete(1, 0);

            assertEquals(new Edit(1, 0, ""), model.reconcile());
        }

        @Test
        @DisplayName("treats a commit as superseding the composition it finishes")
        void commitSupersedesTheComposition() {
            // Compose, commit, finish is the ordinary IME sequence. The word must go out once.
            ImeTextModel model = new ImeTextModel();
            model.composing("hey");
            model.commit("hey");

            assertEquals(new Edit(0, 0, "hey"), model.reconcile());

            model.finishComposing();
            assertNull(model.reconcile(), "the word has already gone");
        }

        @Test
        @DisplayName("types a word the IME finalises without committing it")
        void typesAFinishedComposition() {
            ImeTextModel model = new ImeTextModel();
            model.composing("hey");
            model.finishComposing();

            assertEquals(new Edit(0, 0, "hey"), model.reconcile());
        }

        @Test
        @DisplayName("sends the corrected word, not both")
        void sendsAnAutocorrectAsAnEdit() {
            // Autocorrect: "teh" was composed and committed, then replaced by "the".
            ImeTextModel model = new ImeTextModel();
            model.commit("teh");
            model.reconcile();

            model.delete(3, 0);
            model.commit("the");

            assertEquals(new Edit(2, 0, "he"), model.reconcile(), "the shared 't' is not retyped");
        }

        @Test
        @DisplayName("cannot delete further back than it knows about")
        void cannotDeletePastWhatItKnows() {
            ImeTextModel model = new ImeTextModel();
            model.commit("hi");
            model.reconcile();

            model.delete(50, 0);

            assertEquals(new Edit(2, 0, ""), model.reconcile(), "only what it put there");
        }

        @Test
        @DisplayName("forgets the host once the keyboard has gone")
        void forgetsOnReset() {
            // The next keyboard opens over text this cannot account for, so reconciling against it
            // would send backspaces for characters nobody asked to remove.
            ImeTextModel model = new ImeTextModel();
            model.commit("hello");
            model.reconcile();
            model.reset();

            model.commit("hi");
            assertEquals(new Edit(0, 0, "hi"), model.reconcile());
        }

        @Test
        @DisplayName("keeps its own growth bounded over a long session")
        void boundsItsOwnGrowth() {
            ImeTextModel model = new ImeTextModel();
            for (int i = 0; i < 100; i++) {
                model.commit("0123456789");
                model.reconcile();
            }

            assertEquals(ImeTextModel.CAPACITY, model.sentLength());
        }

        @Test
        @DisplayName("still edits correctly against the text it kept after trimming")
        void editsCorrectlyAfterTrimming() {
            ImeTextModel model = new ImeTextModel();
            model.commit("x".repeat(ImeTextModel.CAPACITY + 10));
            model.reconcile();

            model.delete(1, 0);
            Edit edit = model.reconcile();

            assertNotNull(edit);
            assertEquals(new Edit(1, 0, ""), edit);
        }

        @Test
        @DisplayName("shows the composing word without sending it")
        void showsTheComposingWord() {
            ImeTextModel model = new ImeTextModel();
            model.composing("hel");

            assertEquals("hel", model.composingText().toString());
            assertNull(model.reconcile());
        }
    }
}
