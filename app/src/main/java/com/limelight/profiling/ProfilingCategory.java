package com.limelight.profiling;

import com.limelight.BuildConfig;

/**
 * Compile-time switches for the subsystems that can be traced.
 *
 * <p>Separate flags rather than one global switch because tracing everything at once floods the
 * kernel trace buffer and perturbs the very thing being measured. A latency investigation normally
 * wants one or two of these.
 *
 * <p><b>These are compile-time constants, deliberately.</b> Every one is {@code false} in a release
 * build, so javac folds the guard away and R8 removes the call, the section-name string and the
 * {@link Profiler} method behind it. The alternative - a runtime mask read at each call site - was
 * rejected: on the per-frame path it leaves roughly a dozen acquire loads per frame in the shipped
 * binary, which is the thing this project's rule about hot-path instrumentation exists to prevent.
 * The cost of that choice is that changing what is traced means recompiling, which is a debug-build
 * workflow anyway.
 *
 * <p>The names mirror {@code jni/moonlight-core/profiling.h}, which makes the same decision with
 * the same reasoning for the native side.
 */
public final class ProfilingCategory {

    private ProfilingCategory() {}

    /** Decoder input, output and presentation. */
    public static final boolean VIDEO = BuildConfig.DEBUG;

    /** Opus decode, PCM handoff and the AAudio callback. Not yet instrumented. */
    public static final boolean AUDIO = false;

    /** Mouse, keyboard, controller and the USB driver path. Not yet instrumented. */
    public static final boolean INPUT = false;

    /** Connection establishment, up to the first frame. Not yet instrumented. */
    public static final boolean CONNECTION = false;

    /** App startup, computer polling and box art. Not yet instrumented. */
    public static final boolean UI = false;
}
