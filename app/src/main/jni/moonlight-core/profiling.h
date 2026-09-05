// Native trace markers, mirroring com.limelight.profiling.Profiler.
//
// Compiled out entirely unless MOONLIGHT_PROFILING is defined, which Android.mk does only when
// ndk-build is invoked with NDK_DEBUG=1 - that is, for debuggable variants. In a release build
// there is no ATrace call, no string constant and no branch: the macros expand to nothing, and
// libandroid is not even linked. That matches how the Java side gates the same markers, and it is
// why there is no runtime mask here. A mask would mean an atomic load and a test surviving into
// the shipped binary on the per-frame path, which is exactly what this project's rule about
// hot-path instrumentation is there to prevent.
//
// The cost of that choice is that changing which categories are traced means recompiling. That is
// a debug-build workflow anyway, and ndk-build silently reuses stale objects when only flags
// change - clear app/build/intermediates/cxx after editing the switches below.
//
// NB: only this fork's own native files are instrumented. moonlight-common-c, libusb and mbedtls
// are submodules tracking upstream, and patching them would mean carrying the patches forever.
// For anything inside the stream core, use the stats MoonBridge already exports
// (getRTPVideoStats, getRTPAudioStats, getEstimatedRttInfo) instead.

#pragma once

// Per-category compile-time switches, mirroring com.limelight.profiling.ProfilingCategory.
// Flip one to 0 to drop that category's markers without touching its call sites.
#define ML_PROF_VIDEO 1
#define ML_PROF_AUDIO 0
#define ML_PROF_INPUT 0

#if defined(MOONLIGHT_PROFILING)

#include <android/trace.h>

// 'name' should be a string literal. Building one at runtime would allocate on a hot path.
#define ML_TRACE_BEGIN(cat, name)                     \
    do {                                              \
        if (cat) { ATrace_beginSection(name); }       \
    } while (0)

#define ML_TRACE_END(cat)                             \
    do {                                              \
        if (cat) { ATrace_endSection(); }             \
    } while (0)

#define ML_TRACE_COUNTER(cat, name, value)            \
    do {                                              \
        if (cat) { ATrace_setCounter(name, value); }  \
    } while (0)

// Async sections may be closed on a different thread than the one that opened them, which is how
// a single unit of work is followed across a producer/consumer handoff. 'cookie' distinguishes
// concurrent spans sharing a name and must match between the begin and the end.
#define ML_TRACE_BEGIN_ASYNC(cat, name, cookie)                  \
    do {                                                         \
        if (cat) { ATrace_beginAsyncSection(name, cookie); }     \
    } while (0)

#define ML_TRACE_END_ASYNC(cat, name, cookie)                    \
    do {                                                         \
        if (cat) { ATrace_endAsyncSection(name, cookie); }       \
    } while (0)

#else

// Cast to void so an unused variable passed only to a marker does not warn, and so the macro is
// still a statement that needs its semicolon.
#define ML_TRACE_BEGIN(cat, name)               do { (void)0; } while (0)
#define ML_TRACE_END(cat)                       do { (void)0; } while (0)
#define ML_TRACE_COUNTER(cat, name, value)      do { (void)sizeof(value); } while (0)
#define ML_TRACE_BEGIN_ASYNC(cat, name, cookie) do { (void)sizeof(cookie); } while (0)
#define ML_TRACE_END_ASYNC(cat, name, cookie)   do { (void)sizeof(cookie); } while (0)

#endif // MOONLIGHT_PROFILING
