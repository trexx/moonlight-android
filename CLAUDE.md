# Working on moonlight-trexx

A fork of the Moonlight Android game streaming client, targeting a deliberately narrow set of
hardware. Latency is the product. A change that adds a millisecond to the frame path is a
regression even if every test passes.

Read this before changing code. The rules below are not stylistic preferences — each one exists
because the alternative has cost something here.

---

## Non-negotiables

1. **New functionality ships with tests.** No exceptions for "it's small" or "it's UI-adjacent".
   If the logic genuinely cannot be reached from a JVM test, extract the part that can be — see
   *Testing* below for how that has been done four times already.
2. **Comments are updated with the code they describe.** A stale comment is worse than no
   comment: it actively misleads the next reader. When you change behaviour, re-read every
   comment and Javadoc in the blast radius and correct them in the same commit.
3. **New code carries comments explaining *why*.** Not what — the code says what. Explain the
   constraint, the device that misbehaved, the spec that demands it. Match the density of the
   surrounding file; this codebase comments heavily and deliberately.
4. **Every change states its latency and performance impact.** Say which paths it touches and
   at what frequency. "No impact" is a valid answer, but it must be an answer, not an omission.
5. **New features are instrumented.** Use the existing surfaces (see *Profiling*). A feature
   whose behaviour cannot be observed on a real device cannot be debugged on one.
6. **Dead and legacy code is removed, not left in place.** The supported hardware is narrow and
   known. Version checks, workarounds and fallbacks for devices outside it are dead weight.
7. **Code is modernised as it is touched**, and dependencies are kept current. See
   *Modernisation* for what the source level actually allows here.

---

## Build and test

```bash
./gradlew testDebugUnitTest        # ~30 s, no NDK, no device  (see Testing: unit-testing branch only)
./gradlew jacocoTestReport         # coverage -> app/build/reports/jacoco/  (unit-testing branch only)
./gradlew compileDebugJavaWithJavac # fastest check for Java-only changes
./gradlew assembleRelease lintRelease # what CI gates on; runs ndk-build for both ABIs, slow
```

Toolchain: AGP 9.3.1, Gradle 9.6.1, Java 25 toolchain, NDK pinned in `app/build.gradle`. The JDK
and NDK are downloaded automatically. `git submodule update --init --recursive` is required
before any native build.

Run `lintRelease` explicitly when touching anything UI or API-level related — it catches a
broader issue set than the `lintVitalRelease` that `assembleRelease` triggers on its own.

---

## Performance rules

**Know which path you are on.** These run per frame or faster:

| Path | Frequency | File |
|---|---|---|
| `submitDecodeUnit()` | per decode unit — ≥ once per frame, several times per IDR | `MediaCodecDecoderRenderer.java` |
| Output / render loop | per frame | `MediaCodecDecoderRenderer.java` |
| `ProConController.handleRead()` | ~120 Hz per controller | `ProConController.java` |

These run once, at setup, and are effectively free:

- Everything in `MediaCodecHelper` (`initialize()` and the decoder-capability queries)
- `GlRendererParser` — GPU identification from the GL renderer string
- `StickCalibration.loadLeftStickFlash()` / `loadRightStickFlash()`

On the per-frame paths specifically:

- **No allocation.** No `new`, no boxing, no string concatenation, no lambda capture.
- **No clock indirection.** Read `SystemClock.uptimeMillis()` directly; do not route it through
  an injected clock or supplier. Where a test needs to control time, pass the timestamp in as a
  parameter instead — `VideoStats.getFps(long)` is the pattern to copy.
- **No virtual dispatch added for testability.** Extract pure logic into a separate class that
  the hot path calls once at setup, rather than making the hot path itself polymorphic.
- **No logging.** `LimeLog` on a per-frame path is a per-frame string build.

**Do not let instrumentation perturb what it measures.** The perf overlay is the cautionary
example: when visible, it does ~10 resource lookups, three JNI round-trips and two allocations
on the decode submission thread, once per second. That is why `PerfOverlayListener` exposes
`isPerfOverlayVisible()` — the decoder checks it *before* building anything. Copy that guard
pattern for any new instrumentation, and prefer accumulating cheap counters on the hot path
while doing formatting and aggregation off it.

**Diagnostics, profiling and metrics that cost anything per frame — even when the overlay is off —
belong in debug builds only.** Guard them with `BuildConfig.DEBUG`, which is a compile-time
constant, so the bytecode is absent from release rather than merely unreached. The overlay guard
above handles the cost of *displaying* metrics; it does nothing about the cost of *collecting*
them, and collection is the part that runs sixty times a second. If a metric is worth having in
release, take it from something already paying that cost — `MediaCodec.getMetrics()` exposes the
framework's own decoder latency and frame counters for one call per second, rather than timing
every frame yourself.

**A metric that can be wrong is worse than no metric.** `Average decoding time` read a flat
`0.00 ms` for as long as it existed, because it subtracted moonlight-common-c's clock from
`SystemClock`, so every sample failed the sanity filter. It read `0.00` through a total decoder
hang too, which sent a day of debugging at the network, the host, the CPU and the display before
the decoder. If a number cannot be trusted, delete it rather than leaving it on screen.

**When benchmarking, do not trust overlay numbers.** Harvest the end-of-stream summary
(`globalVideoStats`) instead, so the measurement cost is not attributed to the change. On this
hardware the overlay also forces GPU composition, so it changes frame timing as well as measuring
it — compare overlay-on with overlay-on.

---

## Testing

> **The test suite lives on the `unit-testing` branch and has not merged.** It is not on `master`
> and not on the feature branches, so `testDebugUnitTest` finds no sources and `jacocoTestReport`
> does not exist as a task from here — both commands above silently do nothing. The extractions
> below (`GlRendererParser`, `StickCalibration`) and `VideoStats.getFps(long)` are likewise on that
> branch only; on `master` `getFps()` takes no argument and the two split classes do not exist.
> Everything in this section describes the shape testing takes *there*, and is the target to
> restore to, not a description of the tree you are in.

Unit tests are JVM tests under `app/src/test/java`. They run on any machine, need no device and
no NDK, and are the only automated verification this project has.

**What is testable, and the pattern for getting there.** Several classes were unreachable from a
JVM test until the pure logic was split out. Follow the precedent rather than reaching for
Robolectric:

- `VideoStats` — was coupled to `SystemClock`; now takes the timestamp as a parameter.
- `GlRendererParser` — split from `MediaCodecHelper`, which cannot even load on a JVM because it
  initialises a static field from `Build.HARDWARE`.
- `StickCalibration` — split from `ProConController`, which needs a USB device.
- `KeyMapper` — was already pure.

`LimeLog` wraps `java.util.logging`, not `android.util.Log`, so it is safe to call from code
under test.

**Assertions are disabled in tests** (`enableAssertions = false`), matching Android runtime
behaviour. Do not write a test that depends on an `assert` firing.

**What CI cannot verify, and why you should not try to make it.** Streaming latency is not
measurable on a GitHub runner: there is no hardware decoder, no host, no meaningful network, and
the APK targets `arm64-v8a`/`armeabi-v7a` while runners are x86_64 — the APK will not even run
on a hosted emulator. Do not propose emulator-based latency benchmarks, and do not add an x86
ABI to enable them. Real latency work happens on real hardware; `HARDWARE_TESTING.md` tracks it.

**Record hardware-dependent findings in `HARDWARE_TESTING.md`** rather than fixing them blind.
If a test reveals something whose correctness depends on how a device feels — stick response,
audio sync, decoder behaviour — document it there with the symptom to check, and leave the code
alone until someone can verify it.

CI runs unit tests as a job independent of the build, and reports APK size and DEX method count
against master (`scripts/apk-metrics.py`). Those two numbers are the only performance signal CI
can honestly provide; they move when the shrinker config or dependency tree changes.

---

## Profiling

Instrument new features through the existing surfaces rather than inventing new ones:

- **`VideoStats`** — per-window counters, summed into a session total. Add fields here for
  anything frame-related. Totals only, so windows remain summable; derive rates on demand.
- **`PerfOverlayListener`** — the on-screen overlay. Guard all formatting behind
  `isPerfOverlayVisible()`.
- **End-of-stream summary** — `RendererException` in `MediaCodecDecoderRenderer`, which is what
  surfaces in crash reports and bug reports.
- **`PerformanceHintManager`** (ADPF, API 31+) — `perfHintSession` reports actual work duration
  so the scheduler can pick frequencies. If you add work to the frame path, it belongs inside
  the measured window.

**Reading any of it back on the Homatics takes one step first.** The box ships with
`persist.log.tag=S`, which silences the whole main logcat buffer — not just this app. Even
`adb shell log -t TAG msg` from the shell lands nothing, and only a few whitelisted tags (HDMI,
audio policy) get through. `LimeLog` output is therefore invisible there by default, which reads
exactly like instrumentation that is not firing. Clear the property, then put it back:

```bash
adb shell setprop persist.log.tag '""'   # effective immediately, no reboot
adb shell setprop persist.log.tag S      # the shipped value
```

Crashes never reach the crash buffer on that box whatever the property says — `logcat -b crash`
is always empty. Read them from the dropbox, which keeps entries with timestamps, the package
version and how long the process had been up:

```bash
adb shell dumpsys dropbox --print data_app_crash
```

Its wireless-debugging port also moves between sessions. If `adb connect` is refused, try `:5555`
before concluding the box is down; `ping` settles whether it is actually up.

---

## Target hardware

Supported: **NVIDIA Shield TV** (`arm64-v8a`, Android 11 / API 30) and **Homatics Box R 4K**
(`armeabi-v7a`, Android 14 — 32-bit userspace despite a 64-bit Amlogic S905X4). Both ABIs are
required; neither can be dropped. There is no x86 consumer.

The Shield TV sitting at API 30 is why `minSdk` is 30, and it means **an API-gated feature often
reaches only one of the two devices**. State which when you add one: `PerformanceHintManager`
(API 31) is the existing example — it helps the Homatics and does nothing on the Shield.

- `minSdk 30`, `compileSdk 37`, `targetSdk 34`.
- `targetSdk` is **deliberately** behind `compileSdk`: API 35 force-enables edge-to-edge and
  changes insets handling, which collides with `Game`'s immersive-mode code for no benefit to a
  fullscreen streaming client. Do not "fix" this.

Because `minSdk` is 30, **delete rather than preserve**: `Build.VERSION.SDK_INT` checks below
API 30, support-library fallbacks, and device workarounds for hardware outside the supported set.
Upstream Moonlight supports a far wider range; code inherited from it is routinely dead here.

Keep device quirk entries in `MediaCodecHelper` unless you can show the device is out of scope —
each one traces back to hardware that misbehaved, and removing one without a device to test on is
how regressions get reintroduced.

---

## Modernisation

Source and target level are **Java 25**. Of the modern constructs, only `var` is currently used
anywhere in this codebase (`UsbDriverService`, `XboxWirelessDongle`) — switch expressions,
pattern-matching `instanceof`, text blocks and records appear nowhere yet. Nothing is known to be
broken; they simply have not been reached for.

That means the first use of one is also its first test of the toolchain. Build and run the tests
before leaning on it widely — records in particular rely on D8 desugaring below API 33, which
this project has never exercised.

Most of this codebase is inherited from upstream Moonlight and written in a much older style.
Modernise what you touch, where it makes the code clearer:

- Replace `if`/`else if` chains over a single value with switch expressions.
- Replace `instanceof` followed by a cast with pattern matching.
- Use `var` where the initialiser already names the type, as `XboxWirelessDongle` does.
- Consider records for the small value holders that are currently mutable field bags — but not
  for anything on a per-frame path, where `VideoStats`-style mutable accumulation is deliberate.

Do **not** do sweeping unrelated rewrites. Modernise the code your change already touches, so
the diff stays reviewable and a regression stays attributable. If a file needs a broader cleanup,
that is its own commit.

`app/src/main/jni/` is vendored C from submodules — do not modernise or reformat it.

---

## Dependencies

Renovate manages `gradle` and `github-actions` updates and opens its own PRs. Do not hand-bump
routinely — let Renovate propose it so CI validates the change in isolation. Major version bumps
of the test framework or build plugins in particular belong in their own PR.

Do bump by hand when a version is blocking work, and say why in the commit.
