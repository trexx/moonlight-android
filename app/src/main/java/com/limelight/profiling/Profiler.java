package com.limelight.profiling;

import android.os.Trace;

/**
 * Thin facade over {@link Trace} for this app's own trace markers, for reading in Perfetto.
 *
 * <h2>Call these behind a {@link ProfilingCategory} constant, never unguarded</h2>
 * Every method here is a plain forward to {@link Trace}; the decision about whether to emit
 * anything lives at the call site:
 *
 * <pre>
 *     if (ProfilingCategory.VIDEO) {
 *         Profiler.begin("MC.submitPicData");
 *     }
 * </pre>
 *
 * <p>That shape matters. The constant is {@code false} in release, so javac folds the branch out
 * and R8 then removes the call, the section-name string constant, and eventually this class. A
 * runtime check inside these methods instead would leave the call and its string in the shipped
 * binary and put a load on the per-frame path. See {@link ProfilingCategory} for the full
 * reasoning.
 *
 * <h2>Call site rules</h2>
 * <ul>
 *   <li>Section names must be compile-time constants. Building one by concatenation allocates on
 *       the hot path, which defeats the point even when the branch is folded away in release,
 *       because debug builds are where the measurement actually happens.</li>
 *   <li>Names are capped at 127 characters by the platform; keep them short enough to read on a
 *       Perfetto track.</li>
 *   <li>Pair {@link #begin} with {@link #end} in a {@code finally}, guarded by the same constant.
 *       {@link Trace#endSection()} takes no argument, so an unbalanced pair corrupts every
 *       enclosing section on that thread, not just its own.</li>
 * </ul>
 */
public final class Profiler {

    private Profiler() {}

    /**
     * Opens a trace section on the calling thread.
     *
     * @param name a compile-time constant, 127 characters or fewer
     */
    public static void begin(String name) {
        Trace.beginSection(name);
    }

    /** Closes the section opened by the matching {@link #begin} on this thread. */
    public static void end() {
        Trace.endSection();
    }

    /**
     * Opens an async section, which may be closed on a different thread.
     *
     * <p>This is how one frame is followed across the decode, renderer and Choreographer threads:
     * the whole journey renders as a single span rather than three unrelated slices.
     *
     * @param cookie distinguishes concurrent spans sharing a name; must match at the end
     */
    public static void beginAsync(String name, int cookie) {
        Trace.beginAsyncSection(name, cookie);
    }

    /** Closes the async section with this name and cookie. */
    public static void endAsync(String name, int cookie) {
        Trace.endAsyncSection(name, cookie);
    }

    /** Emits a counter sample, which Perfetto plots as its own track. */
    public static void counter(String name, long value) {
        Trace.setCounter(name, value);
    }
}
