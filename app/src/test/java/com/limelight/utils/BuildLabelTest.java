package com.limelight.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the build label in the settings screen.
 *
 * <p>The label is what a bug report gets read back against, so the cases here are the ones that
 * would send someone to the wrong commit: a build past the tag must not read as the release, a
 * SHA that the build could not determine must not show as anything that looks like one, and the
 * dirty marker must survive into the label rather than being trimmed as decoration.
 */
class BuildLabelTest {

    @Test
    @DisplayName("a release build shows the version and the commit alone")
    void onTag() {
        assertEquals("12.1 (ef241e5)", BuildLabel.format("12.1", 0, "ef241e5", false));
    }

    @Test
    @DisplayName("a build past the tag says how far past")
    void pastTag() {
        assertEquals("12.1+88 (1147b9a)", BuildLabel.format("12.1", 88, "1147b9a", false));
    }

    @Test
    @DisplayName("debug is marked, after the commit")
    void debug() {
        assertEquals("12.1+88 (1147b9a, debug)", BuildLabel.format("12.1", 88, "1147b9a", true));
    }

    @Test
    @DisplayName("a dirty tree's suffix is kept")
    void dirty() {
        assertEquals("12.1+88 (1147b9a-dirty)", BuildLabel.format("12.1", 88, "1147b9a-dirty", false));
    }

    @ParameterizedTest(name = "sha = [{0}]")
    @NullAndEmptySource
    @DisplayName("an unknown commit shows the version alone, not an empty bracket")
    void unknownCommitRelease(String sha) {
        assertEquals("12.1", BuildLabel.format("12.1", 0, sha, false));
    }

    @ParameterizedTest(name = "sha = [{0}]")
    @NullAndEmptySource
    @DisplayName("an unknown commit still marks a debug build")
    void unknownCommitDebug(String sha) {
        assertEquals("12.1 (debug)", BuildLabel.format("12.1", 0, sha, true));
    }

    @ParameterizedTest(name = "sha = [{0}]")
    @NullAndEmptySource
    @DisplayName("a commit count means nothing without a commit, and is not shown")
    void countIgnoredWithoutCommit(String sha) {
        assertEquals("12.1", BuildLabel.format("12.1", 88, sha, false));
    }
}
