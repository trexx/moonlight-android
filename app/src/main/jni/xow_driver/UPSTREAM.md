# xow driver provenance

This directory is a vendored copy of the userspace Xbox One wireless dongle driver
from [medusalix/xow](https://github.com/medusalix/xow), adapted for Android.

    Upstream: https://github.com/medusalix/xow
    Branch:   master
    Commit:   d335d6024f8380f52767a7de67727d9b2f867871
    Date:     2022-04-24
    License:  GPL-2.0-or-later

## Upstream status

xow is in maintenance mode. Its README recommends migrating to
[xone](https://github.com/medusalix/xone), and `master` has not moved since
2022-04-24.

**xone is not a viable replacement for this project.** It is a Linux *kernel* module,
whereas Android apps can only talk to USB devices from userspace. xow's libusb-based
design is what makes dongle support possible in an unrooted app at all, so this
driver stays on xow regardless of upstream's recommendation.

## Local modifications

Do not overwrite these files wholesale from upstream — the Android port is not a
clean copy. Differences from the baseline commit:

* **Added** `xow_driver_jni.cpp` plus JNI hooks in `controller/controller.{h,cpp}`
  (`registerJavaContext`, `inputRumble`, `inputRumbleTrigger`), replacing upstream's
  Linux `uinput` integration.
* **Added** `dongle/firmware.cpp`, which embeds the MT76 firmware blob. Upstream
  loads an external firmware file instead; `Mt76::loadFirmware()` here keeps the
  `firmwarePath` parameter for signature compatibility but ignores it and uses the
  embedded `FW_ACC_00U` array.
* **Added** `Android.mk` for ndk-build.
* **Added** `Dongle::setPairing()` in `dongle/dongle.{h,cpp}` and a JNI entry point for it.
  Upstream reaches pairing mode from exactly one place — an `EVT_BUTTON_PRESS` from the
  adapter's physical button — which leaves no way to pair at all on a unit whose button has
  failed. This exposes the same state Windows sets from "Add a device". It is a mutex-guarded
  wrapper around the inherited `Mt76::setPairingStatus()`, and upstream's two call sites
  (`handleControllerPair`, and the `EVT_BUTTON_PRESS` case in `handleBulkData`) now go through
  it, so the beacon write and the LED command cannot interleave between the two read threads
  and the app. It replaces the port's earlier `using Mt76::setPairingStatus;` re-export, which
  had no callers and allowed the lock to be bypassed.
* **Removed** `controller/input.{cpp,h}`, `utils/reader.{cpp,h}` and `xow.cpp` —
  Linux input-device handling and the standalone daemon entry point, none of which
  apply on Android.
* **Fixed** rumble scaling in `controller/controller.cpp`. `sendRumble()` — part of the JNI
  rumble path added above — mapped the 16-bit magnitude with `>> 9`, giving 0–127 where
  MS-GIPUSB v20240916 §3.1.5.6.1 specifies every motor level as "Percentage, 0 – 100% (0x00 to
  0x64)". Upstream's `RUMBLE_MAX_POWER 100` was left behind unused when `controller/input.cpp`
  was removed; the new `RUMBLE_SCALE` macro uses it. xone reached the same value independently
  (`6ff332d`), having tried 255 in `dbc270b` and reverted it.
* **Added** duplicate-association rejection in `Dongle::handleControllerConnect()`, with a
  `clientAddresses` array in `dongle.h` parallel to `controllers`. Upstream's
  `Mt76::associateClient()` allocates from a free-slot bitmask without comparing the requesting
  MAC, so a controller retransmitting its association request took a second WCID and appeared
  twice. Ported from xone `030f16c`, which fixes the same defect in `xone_dongle_add_client`.
* **Added** `utils/jni.h`, and reworked how the port's JNI callbacks obtain a `JNIEnv`. The read
  threads in `Dongle::readBulkPackets()` now attach once for their lifetime; the callbacks use
  `getAttachedEnv()` instead of an `AttachCurrentThread`/`DetachCurrentThread` pair each. That pair
  ran on every input report — up to ~125 Hz per pad, four pads per adapter — along with a
  `GetObjectClass` and a string-keyed `GetMethodID`. `Controller` now caches its class as a global
  reference and its method IDs in `registerJavaContext()`, which for that reason takes a `JNIEnv`.
  **Nothing on a callback path may call `DetachCurrentThread`**, and local references must now be
  released explicitly — the detach used to do it implicitly.
* **Changed** `Controller::statusReceived()` to forward battery state to Java via a new
  `updateBattery` callback, rather than only logging it. It previously returned early on
  `BATT_TYPE_CHARGING`, which suppressed the very state most worth reporting.

  Note the enum in `gip.h` is misleading and is **deliberately left alone** so the file stays
  byte-identical to upstream and directly refreshable. `BATT_TYPE_CHARGING` (0) does not mean
  charging: GIP's status message carries no charge direction at all. xone reads the same wire
  values as `NONE`/`STANDARD`/`KIT` and treats 0 as "no battery fitted", which is the coherent
  reading and the one `statusReceived()` follows.
* **Fixed** the status message length test in `GipDevice::handlePacket()`. It required exactly
  `sizeof(StatusData)`, but MS-GIPUSB Table 26 allows payloads of `0x04` *or* `0x23`–`0x37`, and
  §3.1.5.5.2.2 requires the extended form on all new devices — whose status messages were therefore
  dropped whole. Now `>=`, matching the `CMD_INPUT` branch that already handled larger packets.

Files that are byte-identical to upstream (for example `controller/gip.h` and
`utils/bytes.h`) can be refreshed directly; the rest need a manual three-way merge.
