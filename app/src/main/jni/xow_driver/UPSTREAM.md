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
* **Removed** `controller/input.{cpp,h}`, `utils/reader.{cpp,h}` and `xow.cpp` —
  Linux input-device handling and the standalone daemon entry point, none of which
  apply on Android.

Files that are byte-identical to upstream (for example `controller/gip.h` and
`utils/bytes.h`) can be refreshed directly; the rest need a manual three-way merge.
