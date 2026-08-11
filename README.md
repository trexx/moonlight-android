# Moonlight Android

Custom build with some PR's merged from the community:
* Support for the "Microsoft Xbox Wireless Adapter for Windows" (Thanks to [summershrimp](https://github.com/summershrimp)) [(PR)](https://github.com/moonlight-stream/moonlight-android/pull/1415) [(Branch)](https://github.com/summershrimp/moonlight-android/tree/xow-support)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord).

## About this fork

This fork tracks upstream Moonlight but adds native Xbox Wireless Adapter support, an
in-stream game menu, a Switch Pro motion driver, a modernised build, and a rebuilt crypto
stack. **It requires Android 11 (API 30) or newer.**

It also carries less than upstream does: GeForce Experience-specific handling, mDNS host
discovery, the in-app help viewer and several settings have been removed outright. See
[Removed features](#removed-features) for the full list and what replaces each.

### Xbox Wireless Adapter support

Connect Xbox One and Xbox Series controllers through the official **Xbox Wireless
Adapter** (the USB dongle) — no Bluetooth pairing, and no root. The adapter is driven
directly over USB with a native driver ported from
[medusalix/xow](https://github.com/medusalix/xow), including the MT76 wireless chipset
driver and the GIP controller protocol, and handles multiple controllers on a single
adapter.

The driver is vendored from **xow `master` @ `d335d602` (2022-04-24)** with Android
adaptations — a JNI bridge in place of the Linux `uinput` layer, and embedded MT76
firmware. Upstream xow is in maintenance mode and has not changed since that commit;
its suggested successor, [xone](https://github.com/medusalix/xone), is a Linux *kernel*
module and therefore cannot be used from an Android app, so this fork stays on the
userspace libusb design. The exact baseline and every local modification are recorded
in [`app/src/main/jni/xow_driver/UPSTREAM.md`](app/src/main/jni/xow_driver/UPSTREAM.md).

### In-stream game menu

Streaming sessions now have a menu, so things that previously required disconnecting,
changing a setting and reconnecting can be done in place.

Open it with the **back button** or by **holding Start** on a controller. It offers:

* **Toggle the performance overlay mid-stream.** Previously the overlay was fixed at
  connection time by a settings checkbox, which meant that diagnosing a stuttering session
  destroyed the very conditions you were trying to observe.
* **Toggle the on-screen keyboard**, and controller mouse emulation.
* **Send special keys** the client would otherwise swallow — ESC, F11, Alt+Enter, Alt+F4,
  Ctrl+V, Ctrl+Shift+Esc (Task Manager), Win, Win+D, Win+G (Game Bar), Win+Shift+Left, and
  Shift+Tab (Steam overlay).
* **Disconnect.**

Two deliberate behaviour changes come with this: **the back button no longer ends the
stream directly** — Disconnect is now a menu entry — and **holding Start opens the menu**
instead of toggling mouse emulation, which is now reached from inside it.

The existing "performance overlay" setting is kept and still works; it now sets the
overlay's *initial* state rather than locking it for the session.

The on-screen keyboard needed two fixes before that menu entry was of any use:

* **It now opens at all.** The IME only shows for a view that is the current IME focus, and
  `StreamView` only became one when the "Soft keyboard text input" setting was on — which
  defaults to off, so on a fresh install the menu item silently did nothing. Commit-text is
  now unconditional and that preference is gone; it never gated the key-event path anyway,
  so the client always emitted UTF-8 text events regardless of it.
* **The d-pad drives the keyboard, not the game behind it.** `onKeyPreIme()` intercepted
  every key and forwarded it to the host, so the IME never saw the navigation meant for it —
  leaving the keyboard visible but unusable. It now stands aside while the IME is up;
  anything the IME declines still reaches the host through normal dispatch.

Based on upstream [PR #1219](https://github.com/moonlight-stream/moonlight-android/pull/1219),
with one correctness fix: that PR's `sendKeys()` sends bare Windows virtual key codes,
where every other keyboard path in the app sends `(0x80 << 8) | vk`. Sunshine masks the
high byte off (`src/input.cpp`, `packet->keyCode & 0x00FF`) so the original works in
practice, but the menu now encodes its packets identically to the physical keyboard path
rather than relying on the host being lenient.

### Broader controller compatibility

The bundled SDL controller database has been refreshed from Valve/SDL upstream, growing
from **529 to 613 known devices** — **84 more controllers** are now correctly identified
rather than falling back to "unknown". Newly recognised hardware includes:

* Steam Deck built-in controls, and the new Valve Steam Controller
* Nintendo Switch 2 Pro Controller
* 8BitDo Pro 3 and Ultimate 2 Wireless
* HORI Wireless HORIPAD for Steam
* Xbox Elite Series 2 over Bluetooth and BLE

Correct identification matters because the controller type is reported to the host, which
uses it to pick the right button glyphs and to enable type-specific handling such as
touchpad and paddle support.

Because SDL adds controllers continuously, a scheduled CI job
([`scripts/check-sdl-controller-db.py`](scripts/check-sdl-controller-db.py)) tracks how
far the vendored copy has drifted and flags when a refresh is worth doing. It also
reports controllers that SDL has *retyped*, which would otherwise silently regress to
"unknown" during a refresh.

Beyond the database, several controllers now work that previously did not:

* **Switch Pro Controller motion sensors over USB.** Android's built-in `hid-nintendo`
  driver exposes buttons and sticks but no motion, so a new USB driver claims the
  controller directly and reports **gyro, accelerometer and rumble** to the host — which
  is what emulators with motion aiming need. It reads the controller's factory *and* user
  stick/IMU calibration out of SPI flash, preferring user calibration when present. This
  is opt-in: because the kernel already claims the pad, it engages only when **both**
  "Xbox 360/One USB gamepad driver" *and* "Override native Xbox gamepad support" are
  enabled. With either off, the controller keeps working through the kernel path exactly
  as before.
* **8BitDo Xbox-compatible pads** are recognised by the USB driver (vendor `0x2dc8`).
* **Xbox Series S/X pads** get their initialisation sequence over USB (PIDs `0x0b05`,
  `0x0b12`, `0x0b13`, plus `0x02fe`), so they start reporting instead of sitting inert.
* **PowerA Pro (Switch)** is mapped correctly. It reports no VID/PID at all, so it is
  matched on device name.

### Low latency audio output (experimental)

Some Android TV devices — the Google TV Streamer among them — deny AudioTrack's fast path
even when it is requested, leaving audio as much as a second behind the video. **Low
latency audio output** in the audio settings replaces AudioTrack with a native
[AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio) output stream.

It is **off by default** and degrades safely: it falls back to AudioTrack when a surround
stream is requested below Android 12L, if the stream fails to open, or if it dies
mid-session and cannot be recovered. With the setting off, the audio path is unchanged.

The renderer is written specifically for this fork rather than ported from an existing
implementation. It uses a lock-free single-producer/single-consumer ring buffer — the
realtime callback touches nothing but `memcpy`/`memset`, with no locks, allocation or
logging — and passes moonlight-common-c's channel mask straight through to AAudio, whose
mask layout is bit-identical, so 5.1 and 7.1 keep their centre, LFE and rear channels.
Route changes are handled by an error callback that reopens the stream off the audio
thread.

Both output paths report the configuration the platform **granted**, rather than the one
they asked for. Requesting `PERFORMANCE_MODE_LOW_LATENCY` is not the same as getting it —
that denial is the entire problem this feature exists to work around — and neither renderer
used to check, so a downgraded stream and a working one produced identical logs. Confirming
which you had meant reaching for `adb shell dumpsys media.metrics`. Now the granted mode,
sharing mode and buffer size appear in the startup log, in a session-end summary that runs
on every exit, and in the performance overlay; a downgrade warns rather than failing, since
there is nothing better to fall back to. That applies to the AudioTrack path too, which had
the same blindness on the path most users are actually on.

None of that instrumentation runs on a hot path in a release build. The realtime callback's
generated code is byte-for-byte identical to what it was before the counters were added; the
underrun figure is instead *derived* from AAudio's own frame counter against the ring's read
index, which costs nothing because both were already being maintained. The per-callback
breakdown, and the AudioTrack write-blocking measurement, are compiled out of release
entirely and exist only in debug builds.

Thanks to [ClassicOldSong/moonlight-android#567](https://github.com/ClassicOldSong/moonlight-android/pull/567)
for the diagnosis; see also upstream issues
[#1423](https://github.com/moonlight-stream/moonlight-android/issues/1423),
[#1238](https://github.com/moonlight-stream/moonlight-android/issues/1238) and
[#1161](https://github.com/moonlight-stream/moonlight-android/issues/1161).

### Hardware-accelerated AES

Stream crypto now runs on **Mbed TLS 3.6.7 LTS** instead of the OpenSSL 1.1.1 build that
reached end-of-life in 2023. The practical wins:

* **Hardware AES.** ARMv8 Cryptography Extensions on arm64 and AES-NI on x86 are compiled
  in and selected at runtime, so AES-GCM and GHASH on the control stream run on dedicated
  silicon rather than in software.
* **Much smaller.** The native library shrank from **2.20 MB to 424 KB** on arm64 — an 81%
  reduction — because only AES-CBC, AES-GCM and a CTR-DRBG are built, instead of a general
  purpose TLS library.
* **Maintainable.** Mbed TLS is a git submodule compiled from source, replacing **22 MB of
  prebuilt static libraries** that were committed to the repository and could not be
  audited or easily updated. Updating it is now a submodule bump.

HTTPS to the host is untouched by this change — that runs on OkHttp and BouncyCastle on
the Java side.

The migration did cost one regression, since fixed: Mbed TLS 3.5.0 made an explicit
`mbedtls_cipher_set_padding_mode()` call mandatory for CBC, and `moonlight-common-c` never
makes it, so every audio packet failed to decrypt — perfect video, no sound. Audio is the
only user of AES-CBC; video, control and RTSP are AES-GCM and were unaffected, which is why
the symptom looked like an audio bug rather than a crypto one. The fix is carried as a
patch against the submodule (see [Carried patches](#carried-patches)).

### Stream encryption is now a setting

The client used to hardcode audio encryption and then silently upgrade to encrypting video
as well whenever the CPU reported AES acceleration. Neither was visible or configurable, and
the host cannot override it downwards — `moonlight-common-c`'s SDP generator enables
encryption if *either* side asks — so a host deliberately configured for no encryption on the
LAN still got encrypted audio.

**Stream encryption** in advanced settings now makes that choice explicit: **None**, **Audio
only** (the default, matching upstream's baseline) or **Audio and video**. On a device
without hardware AES the setting warns that video encryption will run in software, but stays
selectable. What the client asks for is still only half the negotiation: a host that requires
encryption gets it regardless.

Behaviour change worth knowing: on hardware with AES acceleration, video used to be encrypted
automatically and now is not unless asked for.

### Streaming improvements

* **Faster error correction.** `moonlight-common-c` is updated to current upstream, which
  replaces the old Reed-Solomon implementation with [nanors](https://github.com/sleepybishop/nanors)
  and its SIMD-accelerated, runtime-dispatched FEC decoding.
* **More precise frame pacing.** Frame timestamps are now carried end-to-end in
  microseconds rather than milliseconds, removing the 1 ms quantisation that previously
  applied to the presentation timestamps handed to the decoder.
* **Better diagnostics.** The performance overlay now reports FEC recovery — how many
  video and audio packets were rebuilt from parity, and how many were unrecoverable —
  which makes packet loss visible while streaming instead of only showing its symptoms.
  The full RTP counters are also included in the detailed stats output.
* **Decryption failures are counted.** `moonlight-common-c` records no counter when a packet
  fails to decrypt — the paths only log and return — so a stream that decrypts nothing looks
  exactly like a host sending nothing. Both are now counted and surfaced: unconditionally in
  the end-of-stream summary, and in the overlay only when non-zero, so the line's *absence*
  is the healthy signal. That is the surface the silent-audio bug above should have had.
* **Audio failures are counted.** Audio's failure modes are silent by construction: an
  underrun is papered over with silence, a disconnected stream is rebuilt in the background,
  and a full ring drops the incoming buffer. A device could be doing all three continuously
  and nothing anywhere said so. All of them are now counted, alongside the granted output
  configuration, and reported the same way — unconditionally in a session-end summary that
  runs on every exit, and in the overlay only when non-zero. Counters a backend has no
  concept of render as `—` rather than `0`, because a zero is indistinguishable from a real
  count of none.
* **The overlay is built off the decode thread.** Formatting it cost roughly a dozen
  `String.format` lookups, three JNI stats calls and a `TrafficStats` sample once a second on
  moonlight-common-c's decode thread — the frame path the overlay exists to measure. The
  decode thread now takes a snapshot and posts it; the counters themselves stay where they
  are, being integer accumulation on values already in hand.
* **No more Amlogic HEVC corruption.** Amlogic decoders advertise low latency support but
  commonly produce artifacts and decoder hangs when reference frame invalidation is used
  after packet loss — the Onn 4K Plus and Chromecast 4K are both affected. HEVC RFI is now
  enabled on Amlogic hardware only where it is confirmed to behave, which keeps the Fire TV
  Cubes on the fast path without breaking everything else.
* **Game Mode can no longer take the stream down.** Setting the OS Game Mode hint is purely
  advisory, but some devices ship a partial `GameManager` — Meta Quest returns null, some
  OEM builds throw — which crashed the app on connect. It is now best-effort.
* **The renderer thread can no longer wedge in balanced pacing.** Its eviction path checked
  the output buffer queue's size and then blocked on `take()`, so the Choreographer thread
  draining both entries in between left the sole producer waiting on itself — stalling the
  decoder and turning any concurrent codec recovery into a three-way hang. The queue is now
  an int ring buffer whose evict-and-insert is a single call, so the window cannot be
  reopened, and the per-frame `Integer` box goes with it.
* **`+` types `+`.** Android's `KEYCODE_PLUS` is semantic rather than positional and arrives
  without a Shift modifier, so it previously typed `=` on the host.
* **No allocation per controller read.** The USB input loop allocated a packet buffer and a
  `ByteBuffer` wrapper on every read, at roughly 120 Hz per controller. Both are hoisted out
  of the loop.
* **OkHttp 5.x no longer crashes on connect**, and the interrupt its exception translation
  swallows is logged rather than reported as an offline host.

Backported from upstream: [#1219](https://github.com/moonlight-stream/moonlight-android/pull/1219),
[#1461](https://github.com/moonlight-stream/moonlight-android/pull/1461),
[#1478](https://github.com/moonlight-stream/moonlight-android/pull/1478),
[#1516](https://github.com/moonlight-stream/moonlight-android/pull/1516),
[#1565](https://github.com/moonlight-stream/moonlight-android/pull/1565),
[#1582](https://github.com/moonlight-stream/moonlight-android/pull/1582); and from
ClassicOldSong's Artemis fork,
[#571](https://github.com/ClassicOldSong/moonlight-android/pull/571).

### Modernised toolchain

| | Upstream base | This fork |
|---|---|---|
| Android Gradle Plugin | 8.5.1 | **9.3.1** |
| Gradle | 8.7 | **9.6.1** |
| compileSdk | 34 | **37** (Android 17) |
| minSdk / targetSdk | 21 / 34 | **30 / 34** |
| NDK | r27 | **r29** |
| Java bytecode | 11 | **25** |
| Mbed TLS | — (OpenSSL 1.1.1q) | **3.6.7 LTS** |
| libusb | 2024 snapshot | **1.0.30** |
| BouncyCastle | 1.77 | **1.85** |
| OkHttp | 4.12.0 | **5.4.0** |
| jMDNS | 3.5.9 | **removed** (see below) |

Raising the minimum to Android 11 also allowed a substantial cleanup: **111 obsolete OS
version checks** were removed along with the code paths behind them, and the rooted build
flavour — which only ever applied to Android 7.1 and earlier — is gone. Net effect across
the branch is roughly **48,000 lines deleted** against 9,000 added, most of the deletions
being dead compatibility code and committed binaries.

### Removed features

Each of these was removed rather than carried, and each is user-visible:

* **mDNS host discovery.** The jMDNS and `NsdManager` discovery agents are both gone, so PCs
  are added by address through **Add PC Manually** instead of appearing on their own.
* **Non-English locales.** Every translation was dropped — the fork is English-only and takes
  no translations, so lint's `MissingTranslation` check is disabled and upstream's Weblate
  project does not apply to it.
* **GeForce Experience-specific handling.** The 4K-capability check, the SOPS resolution
  workarounds, the >60 FPS launch fudge and the NVIDIA-server detection are gone; the fork
  targets Sunshine.
* **The help button and in-app help viewer.** It opened upstream's wiki in a `WebView`, which
  meant shipping JavaScript execution in a streaming client for one page of documentation
  that a diverged fork's configuration no longer matches. It was the only `WebView` in the
  app, so lint now enforces that with nothing suppressed.
* **Metered-network handling.** Both the second, lower bitrate that was substituted silently
  whenever Android called the network metered, and the warning toast that survived it. The
  supported devices are mains-powered boxes on a fixed LAN.
* **System equalizer support.** Opting in opened an `AudioEffect` session, and cost latency
  twice for it: it disqualified the AAudio path outright and skipped both low-latency
  AudioTrack configurations. Anyone who had it enabled is now eligible for those instead.
* **The "Soft keyboard text input" setting**, as described under the game menu.

Orphaned preference entries are simply never read again; nothing needs clearing by hand.

## Downloads

These are **upstream Moonlight's** releases, not this fork's — the fork builds with an
`.unofficial` application ID suffix and is not published to any store. Build it from source,
or take the signed APK artifact from a [Build workflow](.github/workflows/build.yml) run.

* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio, a JDK 17 or later to run Gradle, and Python 3
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* Build the APK using Android Studio or ‘./gradlew assembleRelease’

The NDK (pinned by ‘ndkVersion’ in app/build.gradle) and the JDK 25 toolchain used to
compile Java are both downloaded automatically. Python 3 is needed at build time for the
patch step below, which runs from `preBuild`.

### Carried patches

Upstream fixes this fork depends on that have not merged yet are kept as diffs under
[`patches/`](patches) and applied to the native submodules' working trees before ndk-build
runs, rather than by forking a submodule or committing into one. The submodule pointer never
moves, so the parent repo still shows exactly which upstream commit is built against.

[`scripts/apply-native-patches.py`](scripts/apply-native-patches.py) does this on every
build. It is idempotent, so an already-patched tree is left alone, and a patch that no longer
applies is a hard error rather than a warning. The cost is that a patched submodule reports
as dirty for as long as the patch is carried; `git submodule update --force` resets it and
the next build re-applies.

Currently carried, both against `moonlight-common-c`: the Mbed TLS 3.x CBC padding and IV
fix, and the decrypt-failure counters.

## Testing

The input, audio and decoder changes in this fork need real hardware and a real host to
verify. [`HARDWARE_TESTING.md`](HARDWARE_TESTING.md) is the checklist — what has been
verified and on what, what is still outstanding, and a table of the hardware that the
remaining items need (a Switch Pro pad, an Amlogic box, a device with the AudioTrack
fast-path bug, and a few others).

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).

The Xbox Wireless Adapter driver is derived from [xow](https://github.com/medusalix/xow)
by medusalix, ported by [Hakusai Zhang](https://github.com/xm1994).
