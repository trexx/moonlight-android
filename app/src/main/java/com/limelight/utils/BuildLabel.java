package com.limelight.utils;

/**
 * Formats the build identity shown in the corner of the settings screen.
 *
 * <p>The version is the nearest {@code v*} tag, which {@code app/build.gradle} reads with
 * {@code git describe} and puts into {@code BuildConfig.VERSION_NAME}. A release build sits on
 * the tag; anything else is some commits past it, and those are what actually get installed on
 * the boxes, so the label says how many - {@code 12.1+88} is 88 commits past 12.1, not 12.1 -
 * and which commit, suffixed {@code -dirty} when tracked files had uncommitted changes, so a
 * build of a work-in-progress tree does not pass for the commit it is based on.
 *
 * <p>The debug build is marked because it installs alongside release and is the one carrying
 * the per-frame instrumentation; which one is on screen is the first thing to establish when
 * reading numbers off a device.
 *
 * <p>Pure so it can be tested; the caller passes {@code BuildConfig} in rather than this class
 * reading it, since the fields are inlined constants that a test cannot vary.
 */
public final class BuildLabel {
    private BuildLabel() {
    }

    /**
     * Returns the label: {@code "12.1 (ef241e5)"} on a tag, {@code "12.1+88 (1147b9a)"} past
     * one, {@code "12.1+88 (1147b9a-dirty, debug)"} for a debug build of an edited tree, or
     * just {@code "12.1"} when the commit is unknown - a build from a source tarball, or a
     * machine without git - in which case {@code commitsSinceTag} means nothing and is ignored.
     *
     * @param versionName     {@code BuildConfig.VERSION_NAME}
     * @param commitsSinceTag {@code BuildConfig.GIT_COMMITS_SINCE_TAG}; 0 on a release build
     * @param gitSha          {@code BuildConfig.GIT_SHA}; null or empty means unknown
     * @param debug           {@code BuildConfig.DEBUG}
     */
    public static String format(String versionName, int commitsSinceTag, String gitSha, boolean debug) {
        boolean hasSha = gitSha != null && !gitSha.isEmpty();
        if (!hasSha && !debug) {
            return versionName;
        }

        StringBuilder label = new StringBuilder(versionName);
        if (hasSha && commitsSinceTag > 0) {
            label.append('+').append(commitsSinceTag);
        }
        label.append(" (");
        if (hasSha) {
            label.append(gitSha);
        }
        if (debug) {
            if (hasSha) {
                label.append(", ");
            }
            label.append("debug");
        }
        return label.append(')').toString();
    }
}
