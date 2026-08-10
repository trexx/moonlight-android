package com.limelight;

import android.util.Log;

/**
 * Logging facade for the app and for moonlight-common-c's Java bindings.
 *
 * <p>Backed by {@link android.util.Log}. It used to wrap {@link java.util.logging.Logger}, on the
 * grounds that the code was shared with non-Android builds of the common library — but there is no
 * such build here: {@code app/src} contains only {@code main}, and nothing outside the Android app
 * references this class.
 *
 * <p>That indirection had a cost that went unnoticed for a long time: <b>a release build emitted
 * nothing at all</b>. Every call vanished, while the same process happily wrote thousands of lines
 * to logcat through other paths, which made a release APK impossible to diagnose in the field —
 * decoder selection, connection stages and outright refusals were all invisible. Going through
 * {@code android.util.Log} sidesteps whatever swallowed it.
 *
 * <p>The fixed tag matters too. The old logger was named after this class, and R8 renames that in
 * release, so even the output that did survive would have been filed under a meaningless letter.
 *
 * <p>None of this is free on a per-frame path: a log call there is a per-frame string build. See
 * the performance rules in CLAUDE.md.
 */
public class LimeLog {
    private static final String TAG = "Moonlight";

    /** Logs routine progress: connection stages, decoder selection, device enumeration. */
    public static void info(String msg) {
        Log.i(TAG, msg);
    }

    /** Logs something unexpected that the app recovered from. */
    public static void warning(String msg) {
        Log.w(TAG, msg);
    }

    /** Logs a failure the user is likely to notice. */
    public static void severe(String msg) {
        Log.e(TAG, msg);
    }

}
