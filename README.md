# Moonlight Android

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## About this fork

This fork tracks upstream Moonlight but adds native Xbox Wireless Adapter support, a
modernised build, and a rebuilt crypto stack. **It requires Android 11 (API 30) or newer.**

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
| jMDNS | 3.5.9 | **3.6.3** |

Raising the minimum to Android 11 also allowed a substantial cleanup: **111 obsolete OS
version checks** were removed along with the code paths behind them, and the rooted build
flavour — which only ever applied to Android 7.1 and earlier — is gone. Net effect across
the branch is roughly **36,000 lines deleted**, most of it dead compatibility code and
committed binaries.

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio and a JDK 17 or later to run Gradle
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* Build the APK using Android Studio or ‘./gradlew assembleRelease’

The NDK (pinned by ‘ndkVersion’ in app/build.gradle) and the JDK 25 toolchain used to
compile Java are both downloaded automatically.

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).

The Xbox Wireless Adapter driver is derived from [xow](https://github.com/medusalix/xow)
by medusalix, ported by [Hakusai Zhang](https://github.com/xm1994).
