package com.limelight.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the token that tells the last dialog in the in-game menu's chain from the rest.
 *
 * <p>Each scenario is a sequence the menu actually produces; the order of opens and dismissals
 * follows the framework's, where a row's action opens the child before the parent's dismissal is
 * delivered.
 */
class DialogChainTest {

    /** Cancel on the root, or Back at the root: nothing else opened, so this ends the chain. */
    @Test
    @DisplayName("dismissing the only dialog ends the chain")
    void rootDismissEndsTheChain() {
        DialogChain chain = new DialogChain();
        int root = chain.opened();

        assertTrue(chain.closes(root));
    }

    /**
     * A row opens a submenu. The child has opened by the time the parent's dismissal arrives, so
     * the parent must not end the chain - that is the case that hid the label the moment any
     * submenu opened.
     */
    @Test
    @DisplayName("a parent's dismissal after its child opened does not end the chain")
    void parentDismissAfterChildOpensDoesNotEndIt() {
        DialogChain chain = new DialogChain();
        int root = chain.opened();
        int child = chain.opened();

        assertFalse(chain.closes(root));
        assertTrue(chain.closes(child));
    }

    /**
     * Back from a submenu reopens the parent as a new dialog, not the old one. The reopened
     * parent is then the live dialog and the submenu's own dismissal must not end the chain.
     */
    @Test
    @DisplayName("reopening the parent on Back is a new generation")
    void backReopensParentAsNewGeneration() {
        DialogChain chain = new DialogChain();
        chain.opened();                       // root
        int child = chain.opened();
        int reopenedRoot = chain.opened();    // Back: root again, as a fresh dialog

        assertFalse(chain.closes(child));
        assertTrue(chain.closes(reopenedRoot));
    }

    /** A token from further back in the chain never closes it, however many opens later. */
    @Test
    @DisplayName("a stale token never ends the chain")
    void staleTokenNeverCloses() {
        DialogChain chain = new DialogChain();
        int first = chain.opened();
        for (int i = 0; i < 5; i++) {
            chain.opened();
        }

        assertFalse(chain.closes(first));
    }
}
