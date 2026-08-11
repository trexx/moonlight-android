# Hardware testing checklist

Everything here builds and passes static verification, but the features added in
`f5d6b07e` ("Last changes from Noir") touch input, audio and decoder paths that can only be
properly exercised on real hardware against a real host. This file tracks what still needs
to be checked and on what.

Tick items off as they are verified, and note the device and host they were verified on.
Anything found broken should be recorded here with the symptom before it is fixed, so the
same case gets re-tested afterwards.

**Legend:** `[ ]` untested · `[x]` verified · `[!]` failed, see note

---

## Test environments

| # | Device | Android | Host | Notes |
|---|---|---|---|---|
| A | *(fill in)* | | | primary Android TV box |
| B | *(fill in)* | | | phone/tablet, for touch + on-screen keyboard |

Record which environment each result came from — several of these behave differently on TV
versus touch devices, and the audio work is Android TV specific.

---

## 1. In-stream game menu

Entry points changed, so the first two are the important regression checks rather than
feature checks.

- [X] **Back opens the menu** instead of ending the stream.
- [X] **Disconnect** in the menu actually ends the stream and returns to the app.
- [X] **Cancel** dismisses and returns to the stream with input still working.
- [ ] **Holding Start on a controller opens the menu.** (This replaced the direct mouse
      emulation toggle.)
- [ ] **Mouse emulation toggle inside the menu** still works and still shows its toast.
- [X] **Toggle Performance Overlay** works mid-stream, and works **repeatedly** — on, off,
      on again. This is the whole point of the feature.
- [X] With the **performance overlay setting enabled**, the overlay is visible from stream
      start. (Deliberate deviation from upstream PR #1219, which deletes the setting.)
- [X] **Toggle On-screen Keyboard** from the menu opens the IME. It runs on a focus retry
      loop, so watch for it firing while the dialog still has focus. This used to do nothing
      at all on a fresh install — it needed the since-removed "Soft keyboard text input"
      setting — so check it on freshly cleared app data, not just a configured device.
- [X] **The d-pad drives the keyboard, not the stream behind it.** This is the fix for the
      original symptom: navigation used to move focus in the game while the keyboard sat
      there unfocused. `StreamView.onKeyPreIme()` now stands aside while
      `imeVisible` is set.
- [ ] **Typed text reaches the host**, including a multi-byte character, swipe typing and
      autocorrect — those go through `commitText` rather than key events.
- [ ] **Backspace deletes on the host** (approximated as backspaces, so watch for it over- or
      under-deleting after autocorrect).
- [ ] **Back dismisses the keyboard** rather than opening the game menu or ending the stream.
- [X] **Input returns to the host once the keyboard is dismissed.** The important regression:
      `imeVisible` is driven by the window insets listener, so if it ever sticks on, *all*
      keys and gamepad buttons stop reaching the host. Press the d-pad and several controller
      buttons after closing the keyboard and confirm the game sees them.
- [ ] **Gamepad buttons still reach the host during normal play**, with the keyboard never
      opened. Every button now passes through `onKeyPreIme` on its way out.
- [ ] **Right-click does not open the menu.** Mouse-sourced `KEYCODE_BACK` is intercepted in
      `Game.handleKeyDown`/`handleKeyUp` before `onBackPressed()` is reached — verify with a
      real mouse, and confirm right-click still registers as a right button press on the host.
- [ ] **The soft keyboard never appears on its own.** Start a stream, exit, start another,
      several times over. This was intermittent before `.Game` gained
      `windowSoftInputMode="stateAlwaysHidden"`: `StreamView` always reports itself as a text
      editor, so with no state policy the framework was free to restore the IME on focus gain.
      Then confirm the menu entry still *opens* it — `stateAlwaysHidden` governs focus gain
      only and must not have disabled explicit shows.
- [ ] **Pointer capture survives focus loss.** With a mouse attached, open the game menu,
      dismiss it, and confirm the host cursor does not stop at the screen edges. Nothing in
      the PiP/multi-window removal should have touched `onWindowFocusChanged`, and this is the
      check that proves it.
- [ ] **No multi-window.** The stream opens fullscreen with no system bars, and they stay
      hidden after opening and dismissing the game menu. On a phone, split-screen is refused.

### Special keys

Each of these exercises modifier accumulation in `sendKeys()`, so test on the host rather
than assuming. Multi-key combos are the ones worth the time.

- [ ] ESC
- [ ] F11 (toggle full screen)
- [ ] Alt+Enter (toggle full screen)
- [ ] Alt+F4 (closes the focused window)
- [ ] Ctrl+V (pastes)
- [ ] Ctrl+Shift+Esc (opens Task Manager) — three keys, worst case for modifier ordering
- [ ] Win (opens Start menu)
- [ ] Win+D (switches to desktop)
- [ ] Win+G (opens Game Bar)
- [ ] Win+Shift+Left (moves window to the left display) — needs two displays
- [ ] Shift+Tab (opens the Steam overlay, in a Steam game)

> Sunshine masks the high byte off the key code, so a wire-encoding mistake here would not
> show up against Sunshine. If a host other than Sunshine is available, retest a couple of
> these against it.

---

## 2. Switch Pro Controller over USB

This driver only engages with **both** "Xbox 360/One USB gamepad driver" **and** "Override
native Xbox gamepad support" enabled, because Android's `hid-nintendo` already claims the
pad. Test both configurations — the second block is the regression check.

**With the USB driver active:**

- [ ] All buttons map correctly (face, shoulders, triggers, sticks-as-buttons, D-pad,
      +/−/Home/Capture).
- [ ] **Both sticks cover their full range and centre correctly.** This is the real test of
      the SPI flash calibration parsing — bad parsing shows up as a dead zone that is too
      large, a range that clips early, or a drifting centre.
- [ ] Rumble fires from the host.
- [ ] **Gyro reaches the host** and moves in the right direction on all three axes.
- [ ] **Accelerometer reaches the host with the right magnitude.** At rest, one axis should
      read approximately **9.8**, not 1.0. Noir's driver reported g where `Limelight.h`
      specifies m/s²; that was fixed during the port and this is the check that confirms it.
- [ ] Motion is usable in practice — e.g. motion aiming in an emulator such as Cemu, Yuzu or
      Ryujinx.
- [ ] Unplug and replug mid-stream: the controller comes back, and the app does not leak the
      USB interface or crash.
- [ ] **Buttons, sticks and motion all still work after the read-loop buffer was hoisted.**
      The USB read now reuses one ByteBuffer instead of allocating per read. handleRead()
      only uses absolute get(int), so nothing should carry between reads — but this landed
      without a controller to test on, and a stale or mis-windowed buffer would show up as
      stuck inputs or garbage motion.

**With "Override native Xbox gamepad support" off (default):**

- [ ] The controller still works through the kernel `hid-nintendo` path exactly as before,
      with buttons and sticks but no motion. This must be unchanged for existing users.

---

## 3. Low latency audio output (AAudio)

Off by default. The first item is what makes this safe to ship; the rest only matter once
it is switched on.

- [X] **Setting off: audio behaviour is byte-for-byte unchanged.** No new logging, no
      change in latency, no change in device selection.
- [x] Setting on, **stereo**: audio plays, and stays in sync across a long session — 30+
      minutes — with no dropouts, crackle or drift. *(Verified on the Homatics Box R 4K.)*
- [ ] Setting on, **5.1 or 7.1**: **every speaker produces sound.** Use Windows'
      per-speaker test (Sound → Speakers → Configure → Test). This is the exact check that
      exposed the silent-surround-channels bug in ClassicOldSong #567.
- [ ] Setting on, **route change mid-stream**: unplug/replug HDMI, or switch audio output.
      Audio must recover, or fall back to AudioTrack for the rest of the session — it must
      not go permanently silent, and the stream must not hang at "Waiting for audio stream
      establishment".
- [ ] Setting on, **surround below Android 12L** (API 32) if such a device is available:
      must fall back to AudioTrack rather than opening a stream with an undefined layout.
- [ ] Under load — packet loss, decoder pressure — audio does not stutter. Two independent
      reports of unplayable stuttering exist against the implementation this replaces, so it
      is worth deliberately stressing.

> **Cannot be verified on the hardware available.** The problem this targets is AudioTrack's
> fast path being denied on certain Android TV devices, producing roughly 0.5–1 s of audio
> delay. The Android TV box on hand does **not** exhibit that symptom, so the latency win
> itself is unconfirmed. Everything above is still worth running — it establishes the
> feature does no harm — but the benefit remains unproven until it reaches an affected
> device (Google TV Streamer or similar).

---

## 4. Backport regression checks

Small changes, but each touches a path that is easy to break silently.

- [ ] **Xbox Series S/X pad over USB** (PID `0x0b05`/`0x0b12`/`0x0b13`) initialises and
      reports input. Previously inert.
- [ ] **8BitDo Xbox-compatible pad over USB** is claimed and works.
- [ ] **PowerA Pro (Switch)** maps correctly. It reports no VID/PID, so it is matched on the
      device name `"Lic Pro Controller"` — confirm the name matches on the device to hand,
      since a rename would silently disable the mapping.
- [x] **Xbox Wireless Adapter still works.** The USB claim chain gained a branch; confirm
      no regression to the existing dongle path, including multiple controllers.
      *Verified on the Homatics Box R 4K Plus (adapter `045e:02e6`): claims, loads firmware,
      brings the radio up, and an already-paired controller reconnects on its own.*
- [ ] **"Pair Xbox Wireless Controller" in the game menu.** Only appears when an adapter is
      claimed and running — check it is **absent** with the adapter unplugged, and with the
      "Xbox 360/One USB gamepad driver" setting off. When selected, the adapter's LED must
      start blinking (`Pairing enabled` in logcat) and a controller must be able to pair.
      This exists because the adapter's physical pairing button is dead on at least one unit,
      so open the menu with the **TV remote's Back button** — needing a working pad to reach
      the thing that pairs a pad would defeat the point.
- [ ] **Pairing mode does not disturb a connected pad.** Trigger it while a controller is
      already connected, and trigger it twice in a row.
- [ ] **Gamepad rumble still works.** The phone-vibrator fallback was removed along with the
      other phone-only fallbacks, but the `VIBRATE` permission had to stay, because
      `InputDevice.getVibrator()` goes through the same `Vibrator` API. If that permission were
      ever dropped, gamepad rumble is what breaks — so this is the check that guards it.
- [ ] **Gamepad gyro still reaches the host** on a pad that has one (DualSense/DualShock). The
      device-IMU fallback and its axis-swizzle are gone; a real pad reports in its own frame
      and needs no correction.
- [ ] **The settings screen renders** with `checkbox_enable_pip`, `checkbox_vibrate_fallback`,
      `seekbar_vibrate_fallback_strength` and `checkbox_gamepad_motion_fallback` all removed —
      a dangling preference key would crash it or leave a dead row.
- [ ] **`+` on a hardware keyboard types `+`** on the host, not `=`. Test on a layout where
      `+` is its own key rather than Shift+`=`.
- [ ] **HEVC on Amlogic hardware** (Onn 4K Plus, Chromecast 4K, or a Fire TV Cube): stream
      HEVC with deliberate packet loss and confirm no artifacts or decoder hang. On Fire TV
      specifically, confirm RFI is still enabled — check logcat for
      `Enabling HEVC RFI on confirmed-safe Amlogic device`, since that path is the one at
      risk of having been regressed.
- [ ] **Game Mode:** on Android 13+, connect and confirm the stream starts. On a device with
      a partial `GameManager` (Meta Quest, some OEM builds), confirm it no longer crashes.

---

## 5. Audio decryption (AES-CBC under mbedTLS)

Audio is the only stream encrypted with AES-CBC — video, control and RTSP all use AES-GCM.
When CBC breaks, the picture is perfect and only the sound disappears, with no error shown
to the user, so this needs checking explicitly rather than assuming "video works" means
crypto works.

Requires a paired host actually streaming. The failure mode is silent, so run the log check
rather than trusting your ears alone: a host sending no audio at all looks identical.

- [ ] **Sound is present**, and stays in sync over a long session.
- [ ] **No decrypt failures.** Native `moonlight-common-c` logging is not stripped in release
      builds, so this works on a normally signed release APK:
      ```bash
      adb logcat -d | grep -a "Failed to decrypt audio packet"   # must return nothing
      ```
      Note the Homatics ships with `persist.log.tag=S`, which silences the whole main
      buffer. Clear it first (`adb shell setprop persist.log.tag '""'`) and put it back to
      `S` afterwards, or this check silently passes whatever the truth is.
- [ ] **Audio is reaching the HAL**, not merely decrypting:
      ```bash
      adb shell dumpsys media.audio_flinger | awk '/name AudioOut_D,/,/Hal stream/' \
        | grep -E "Standby|Frames written"
      ```
      `Frames written` must advance between samples and `Standby` must be `no` while
      streaming. A started-but-starved AudioTrack is exactly what the CBC padding bug
      produced.
- [ ] **Keyboard and mouse input still reach the host.** The carried patch also changes IV
      handling for non-GCM contexts, which is the pre-Gen 7 input encryption path. Hosts new
      enough to use Gen 7+ input will not exercise it, so this only proves out against an
      older host.
- [ ] **Decrypt-failure counters read zero.** The end-of-stream summary now ends both RTP
      lines with a decrypt-failed count, and the overlay shows a line only when one is
      non-zero — so on a healthy stream that line should be *absent*, not zero. To prove the
      counter actually moves, remove `MBEDTLS_CIPHER_PADDING_PKCS7` from
      `moonlight_mbedtls_config.h`, rebuild, and confirm the audio count climbs.

### Stream encryption setting

Encryption is now chosen explicitly instead of being decided by `hasFastAes()`. What the
client asks for is only half the negotiation — the host can still require it — so check the
log rather than assuming, per the commands above.

- [ ] **Audio only (default):** audio encrypted, video not.
- [ ] **None:** no encryption requested. If the host requires it anyway, expect
      `Enabling audio encryption by host request despite client opt-out` — that line appearing
      is proof the opt-out path works, not a failure.
- [ ] **Audio and video:** both encrypted, and the stream still performs acceptably. On
      hardware without AES acceleration this is the setting the warning is about.
- [ ] **The warning appears in settings** on a device where `hasFastAes()` is false, and the
      option remains selectable rather than disabled.
- [ ] Audio still works across all three, since changing this changes which cipher path runs.

---

## 6. Copy-free picture data submission

`43a626ef` stopped routing picture data through a Java `byte[]`. Native now takes the address of
the codec's own input buffer and `memcpy`s the decode unit's buffer list straight into it
(`bridgeDrStartPicData` → `memcpy` → `bridgeDrSubmitPicData`). Parameter sets still take the old
path. The commit shipped explicitly **not validated on hardware**; this section is that validation.

Two things make this different from the other sections here:

- **A clean run proves nothing unless you can show it reached the risky case.** Native writes at
  the buffer's *position*, which is non-zero only when codec-specific data has been prepended for a
  fused IDR frame. IDRs are rare on a healthy stream, so a ten-minute session can easily never test
  the offset arithmetic at all and look identical to one that did. Debug builds therefore report
  `Fused CSD frames`, `Picture data aborts` and `Picture data invariant failures` — **read them, and
  treat a run with `Fused CSD frames: 0` as not having tested anything.**
- **The failure is corrupt video, not a crash**, which on a lossy link is easy to mistake for
  ordinary packet loss. Hence the invariant counter rather than judging by eye.

Debug builds log the whole stream summary at teardown (`Stream summary:` via `LimeLog.warning`),
which is where all of the counters below come from. Release builds do not — the summary is
otherwise only reachable by crashing.

```bash
adb shell setprop persist.log.tag '""'    # Homatics only; restore to S afterwards
adb logcat -d | grep -a -A40 "Stream summary:"
```

### 6.1 Smoke, per device × codec

- [ ] **H.264** streams cleanly for 3 minutes.
- [x] **HEVC** streams cleanly for 3 minutes.
      *Verified on the NVIDIA Shield TV (mdarcy, Android 11) against Sunshine on Windows,
      2026-08-10. 4K60 HDR — `Format: 200` (H265_MAIN10), `OMX.Nvidia.h265.decode`, low-latency on,
      80 Mbps, 415 s, 16172 decode units. `Frames in-out: 16172, 16162`, `Frame losses: 0 in 0 loss
      events`, RTP `136220` packets with 0 failed/OOS/invalid/decrypt-failed,
      `Picture data invariant failures: 0`, `Picture data aborts: 0`, no native bailouts.*
- [ ] **AV1** streams cleanly for 3 minutes. Distinct risk class: AV1 has no separate parameter set
      NALUs, so *all* of its bytes take the new path and the sequence header rides inside the
      picture data. Confirm `CSD stats: 0, 0, 0` — a non-zero value there means the fused branch is
      prepending on AV1 and the offset risk applies to it too.
      *Not testable on the Shield: the summary reports `AV1 Decoder: (none)`. Covered on the
      Homatics instead — see below.*

> **AV1 partially verified on the Homatics, 2026-08-11, release build.** `c2.amlogic.av1.decoder`
> (hardware), `video/av01`, 1920x1080, **45020 frames over 750 s**, ending `error_state: STARTED`
> with no error code. Neither native bail-out appeared (`Codec input buffer is not direct`,
> `exceeds input buffer capacity` — these are `__android_log_print` in `callbacks.c` and are *not*
> stripped from release, which is what makes this checkable without a debug build). No dropbox
> crash. The stream took genuine packet loss during the run — 25 `Invalidate reference frame`
> requests — and kept decoding.
>
> This matters because AV1 is the codec where **every byte takes the new path**: no separate
> parameter set NALUs, so `submitCsdBeforePicData` returns `CSD_NOT_NEEDED` and the sequence header
> rides inside the picture data.
>
> **Still outstanding for AV1:** the byte-level invariant counters, which need the instrumented
> debug build. Note the offset risk does *not* apply here — with no CSD to prepend the write offset
> is always 0 — so what the counters would add is confirmation of length and of a valid OBU header
> at the write offset, not the fused-offset case.
>
> **Getting AV1 at all needs a host that can encode it.** An earlier attempt silently negotiated
> H.264; confirm with `dumpsys media.metrics`, which records the codec on *release*, so stop the
> stream before checking. Release builds strip `LimeLog.info`, so logcat will not name the codec.
- [x] `Frames in-out: N, M` — the gap stays small and constant. A **growing** gap means frames enter
      the codec and never come out, which is the signature of a wrongly-offset write.
      *Shield/HEVC: gap of 10, flat across 16172 frames.*
- [x] `Picture data invariant failures: 0` on every run. *Shield/HEVC: 0.*
- [x] These two native errors never appear — either is a hard fail:
      ```bash
      adb logcat -d | grep -a -E "Codec input buffer is not direct|exceeds input buffer capacity"
      ```
      *Shield/HEVC: neither appeared.*

> **The 2026-08-10 Shield run passed everything above and still tested nothing that matters.**
> `Fused CSD frames: 0` and `CSD stats: 1, 1, 1` — one VPS, one SPS, one PPS, so **exactly one IDR
> in 415 seconds**. That IDR took the `CSD_SUBMITTED` branch and wrote at position 0, meaning the
> non-zero-offset arithmetic ran zero times out of 16172 decode units. Without those counters the
> run reads as a full pass. This is the case 6.2 exists for, and on a clean link it is the *normal*
> outcome, not an unlucky one.

### 6.2 Forced IDRs — the offset case

`fusedIdrFrame` is `FEATURE_AdaptivePlayback`, which both target decoders report, so every IDR
*after the first since the last codec configure* prepends CSD and hands native a non-zero position.
Note the "since the last configure": `configureAndStartDecoder()` resets `submittedCsd`, so each
codec recovery restarts that sequence and you need two IDRs after one before the fused path fires.

Force IDRs with packet loss. It has to be applied on the **host**, not the device — these boxes have
no `tc` and are not rooted. The host is Windows, so there is no `netem`; use
[clumsy](https://jagt.github.io/clumsy/) (WinDivert-based, needs Administrator):

```
Filter:  outbound and udp and (udp.SrcPort == 47998 or udp.DstPort == 47998)
Drop:    checked, Chance 3%
```

47998 is the video stream's UDP port (`RtspConnection.c:1278`); scoping the filter to it keeps the
loss off RTSP and control, so the session degrades the way real packet loss does instead of
collapsing. 3% forces several IDRs a minute while staying watchable — 1% is a slow drip, 5%
saturates.

**Confirm the shaper is actually doing something** before trusting a clean result: `Frame losses: N
in M loss events` must be climbing in the summary. A filter that matches nothing looks exactly like
a passing test.

If installing a kernel-mode driver on the host is not acceptable, these produce IDRs with no extra
software — fewer at a time, so they suit 6.1 rather than the ≥30 this section wants:

- Alt-Tab away from the game and back
- Toggle the game between fullscreen and windowed
- Change the host's resolution or refresh rate
- Win+Alt+B (HDR toggle) — this one also forces a codec restart, see 6.3

- [x] ≥30 IDRs in the run (`grep -c "IDR frame request sent"`), with `Fused CSD frames` climbing
      alongside `Frame losses: N in M loss events`.
      *Shield/HEVC 4K60 HDR, 2026-08-10: 8 IDRs carrying CSD, of which **6 took the fused branch**.
      Fewer than 30, but the offset case is now genuinely covered rather than assumed — see the
      consistency check below.*
- [x] `Picture data invariant failures: 0`.
      *0 across 21893 decode units including all 6 fused writes. Each was checked for correct
      length, correct start offset, and a valid Annex-B start code at the write offset.*
- [ ] No corruption at IDR boundaries **that a baseline build does not also show under the same
      loss** — run `HEAD~1` at the same clumsy drop chance as the control, because packet loss
      produces its own artifacts.
- [ ] Repeat on H.264 **and** HEVC: HEVC adds a VPS, so the prepended CSD length differs and so
      does every offset derived from it. *HEVC done; H.264 outstanding.*

> **What actually worked, 2026-08-10.** Not packet loss. A **host-side refresh-rate change**
> reinitialised the Sunshine encoder and produced a run of IDRs *without* restarting the client's
> codec, which is the only condition that reaches the fused branch. The host game fell over doing
> it; the client did not, and that is what generated the 3 loss events.
>
> **Do not use the HDR toggle for this.** It restarts the codec, and `configureAndStartDecoder()`
> resets `submittedCsd` (line 663), so the IDR that follows a restart always takes the
> `CSD_SUBMITTED` branch at position 0. Each toggle supplies one IDR *and* resets the flag that
> would have made the next one fused, so twenty toggles yield twenty IDRs at position 0 and
> `Fused CSD frames: 0`. The toggle is for 6.3; it is actively counterproductive here.
>
> **The counters cross-check each other**, which is worth knowing when reading a future run:
> `CSD stats` counts IDRs that carried parameter sets, and every such IDR either prepends or does
> not. This run: 8 IDRs = 2 at position 0 (stream start, plus one after the single codec restart)
> + 6 fused. If those do not add up, either the model or the instrumentation is wrong.

If RFI is soaking up the losses (`Invalidate reference frame request sent` instead of `IDR frame
request sent`), switch to a codec where `refFrameInvalidationActive` is false, or the run will not
produce the IDRs this test depends on.

**On the Shield that escape hatch does not exist.** Measured 2026-08-10: RFI is active for *both*
codecs there —

```
Decoder OMX.Nvidia.h264.decode will use reference frame invalidation for AVC
Enabling HEVC RFI based on low latency option support
Decoder OMX.Nvidia.h265.decode will use reference frame invalidation for HEVC
```

so packet loss is recovered by invalidating reference frames rather than by a full IDR, and adding
loss will not reliably produce the IDRs this section needs. The summary confirms it end to end:
`RFI active: true`, `Fused IDR frames: true`, and one IDR across a 415 s stream.

That leaves the **host-side encoder triggers** listed above — resolution or refresh-rate change,
fullscreen toggle, Alt-Tab — as the route that works. They produce genuine IDRs without going
through loss recovery *and* without restarting the client's codec, which is the combination the
fused branch requires. A refresh-rate change is the one confirmed to work on 2026-08-10.

### 6.3 Codec recovery — the memory-safety window

Between `bridgeDrStartPicData` returning and `bridgeDrSubmitPicData`, native holds a raw pointer
into a codec input buffer. The commit argues recovery cannot free it underneath, because the
quiesce barrier is only reached from `fetchNextInputBuffer()` and `queueNextInputBuffer()`, one on
each side of the window. **That is an argument, not a test.**

There is a free detector for it: `doCodecRecoveryIfRequired()` sets `nextInputBuffer = null`, and
`submitPicData()` dereferences it without a guard, deliberately. So the failure is a
`NullPointerException` naming `submitPicData` rather than silent memory corruption.

The repeatable provocation is `setHdrMode()`, which promotes to a full codec restart on every HDR
metadata change and resets the attempt counter, so it never exhausts its retries. Toggle HDR on the
Windows host with **Win+Alt+B** (or Settings → System → Display → Use HDR) ~20 times during a
stream.

Preconditions, all of which silently produce zero recoveries if missed — check the log line, not
the intent: HDR must be enabled in Moonlight's stream settings, the host display must be
HDR-capable, and the client must have negotiated an HDR-capable codec (HEVC Main10 or AV1, not
H.264). Confirm `Codec recovery attempt: 1` appears after the first toggle before doing the other
nineteen.

- [ ] ≥20 `Codec recovery attempt: N` lines, stream recovers each time.
      *Only 1 so far (Shield 2026-08-10). The quiesce barrier engaged as designed —
      `Waiting to quiesce decoder threads: 6` then `Codec recovery attempt: 1` then
      `Trying to restart decoder after CodecException` — and the stream recovered. One cycle is
      not twenty; this box stays open.*
- [ ] **No** `NullPointerException ... at ...MediaCodecDecoderRenderer.submitPicData`. This is the
      one signature that falsifies the commit's memory-safety argument outright.
      *None in that single cycle. Open until there are enough cycles to mean something.*
- [ ] Nothing in the dropbox (`logcat -b crash` is always empty on the Homatics):
      ```bash
      adb shell dumpsys dropbox --print data_app_crash
      ```
- [ ] Run HDR toggling **and** 3% loss together for 10 minutes. The interaction is where a stale
      offset hides: recovery resets `submittedCsd`, so the next IDR writes at 0 and the one after
      writes at the CSD length.

### 6.4 Abort and buffer reuse

`abortPicData()` clears the retained buffer; `fetchNextInputBuffer()` short-circuits while it is
non-null. If the clear were missed, a retried fused IDR would prepend CSD twice and shift every
subsequent write by a constant.

- [x] `Picture data aborts` is **non-zero** — otherwise this path was never exercised and the box
      below is not a pass. Aborts occur during recovery quiesce, so 6.3 is the way to provoke them.
      *Shield 2026-08-10: 1 abort, during the codec restart from the HDR toggle.*
- [x] `Picture data invariant failures: 0` across a run containing aborts.
      *0. The fused IDRs that followed the abort did not double-prepend CSD — that failure would
      have shifted every subsequent write by the CSD length and lit this counter up.*

### 6.5 CheckJNI

The commit adds a new JNI protocol: an object reference held across a call, `GetDirectBufferAddress`
/`GetDirectBufferCapacity`, a `DeleteLocalRef` and three new upcalls. CheckJNI validates all of it.

```bash
adb shell setprop debug.checkjni 1
adb shell am force-stop com.limelight.debug
# relaunch, stream 10 minutes with forced IDRs and one recovery
adb logcat -d | grep -iE "checkjni|JNI ERROR|JNI WARNING"
adb shell setprop debug.checkjni 0
```

- [x] `Late-enabling -Xcheck:jni` appears — **without this line CheckJNI was not on** and the run
      proves nothing.
      *Confirmed on the Shield 2026-08-10: `limelight.debu: Late-enabling -Xcheck:jni`. Note the
      tag is the truncated process name, not the package — grep for `Late-enabling`, not for
      `limelight`.*
- [x] No JNI errors naming `bridgeDrStartPicData`, `bridgeDrSubmitPicData`, `bridgeDrAbortPicData`,
      `GetDirectBufferAddress` or `java/nio/Buffer.position`.
      *Shield 2026-08-10, `debug.checkjni 1` confirmed engaged: clean across two streams totalling
      ~38k decode units, 1 codec restart and 1 abort — so all three new upcalls were exercised, not
      just the two on the steady-state path.* Compare any complaint against a
      `HEAD~1` build before treating it as new — the `DetachCurrentThread`-with-pending-exception
      pattern predates this commit.

### 6.6 Performance — what is and is not measurable

The change removes one `memcpy` of an average 20–40 KB frame: roughly **10–25 µs per frame, ~0.1% of
one core at 60 fps**.

**This is not observable as latency and must not be reported as such.** `getAverageEndToEndLatency()`
is integer milliseconds; the change is a rounding error within it. `getAverageDecoderLatency()`
returns 0 unless the build is debug *and* the overlay is visible.

Measure the CPU cost on the submitting thread instead. That thread is **`VideoRecv`**, not
`VideoDec` — moonlight-common-c only creates `VideoDec` when `CAPABILITY_DIRECT_SUBMIT` is absent,
and both target decoders have it, so `VideoDec` does not exist here at all.

```bash
PID=$(adb shell pidof com.limelight.debug | tr -d '\r')
adb shell "grep -H . /proc/$PID/task/*/comm" | grep -i VideoRecv
adb shell "cat /proc/$PID/task/<TID>/schedstat"   # field 1 = sum_exec_runtime, ns
```

- [ ] `simpleperf` shows the `SetByteArrayRegion` frame beneath `BridgeDrSubmitDecodeUnit` present
      in a `HEAD~1` profile and **absent** at `HEAD`. A frame disappearing is unambiguous in a way a
      sub-percent timing delta is not; this is the primary evidence.
      *Blocked. Release builds are not `profileable`, so simpleperf refuses them, and debug builds
      are useless for this because ART forces CheckJNI on for any debuggable app — see below.
      Needs `<profileable android:shell="true"/>` in the manifest and a rebuild of both sides.*
- [x] Paired `schedstat` runs (5 per build, alternating, fixed 300 s, fixed scene, overlay state
      identical) report a mean and a spread. **If the spread swallows the difference, say exactly
      that** — per CLAUDE.md, a metric that can be wrong is worse than no metric.
      *Done 2026-08-11, Shield, release builds, 120 s paired windows, 4K60 HEVC, overlay off.*

**Result: no measurable difference, in either direction.** Six 90-120 s windows on the Shield,
release builds either side of the change, alternating between them, 4K60 HEVC over wired Ethernet,
overlay off. Normalising submit-thread CPU by bytes received:

| | baseline | HEAD |
|---|---|---|
| mean | 9.81 ns/byte | 9.96 ns/byte |
| sd | 0.35 | 0.58 |
| range | 9.56 - 10.21 | 9.33 - 10.47 |

Difference in means +1.5%, t = 0.38 against ~2.8 needed for significance at this sample size. The
best-matched pair, where bytes received agreed to 0.1% (794/787 MB vs 793/787 MB), differs by
**-0.4%**. The 95% interval on the difference spans roughly -10% to +12%, so the honest claim is
**"no effect larger than about 10% either way on that thread"**, not "no effect".

**A single unpaired comparison suggested a 4-5% regression and it was noise.** That pair had HEAD
measured before baseline and content that differed by 1.5% in bytes, and it landed inside the
baseline's own later spread. One run per build is not enough here whatever it appears to show;
alternate the builds and normalise, or do not draw the conclusion.

**Normalise by bytes received, not by the codec threads.** `submit/codec` swung from 0.58 to 1.31
across sessions purely on content, because codec CPU tracks frames while the submit thread tracks
bytes. It is only meaningful within a single session. Bytes come from `/proc/net/dev` on the
interface carrying the stream, sampled either side of the window.

**Content intensity dominates everything and must be reported.** Between sessions here the stream
ranged from 34 to 70 Mbps and submit-thread CPU from 7.9 to 90 ms/s - an order of magnitude - while
the effect under test is a few percent. Static content is worst: it minimises frame size, which is
exactly what the removed copy scales with, so the first attempt at this measured a regime where the
change cannot matter. Use sustained high-motion content and record the Mbps alongside any result.

**What this means for the change.** It is a null result on CPU, not evidence against the change.
The second copy is provably gone from the code and the correctness work stands; what the numbers say
is that at realistic frame sizes the saving does not clear the measurement noise on this hardware,
so the commit should be described as structural rather than as a measurable CPU win. Note also that
the change trades one bulk copy for several JNI operations per frame - two upcalls plus
`GetDirectBufferAddress`, `GetDirectBufferCapacity` and a `position()` call - which plausibly offsets
some of the saving and is consistent with measuring nothing.

**On the Homatics the change is real: about 6% off the submit thread, reproduced twice.**
H.264 at 4K, release builds, alternating, two 90 s windows each, normalised by bytes received on
`wlan0`:

| bitrate | baseline | HEAD | delta |
|---|---|---|---|
| 82 Mbps | 10.26 ns/byte | 9.65 ns/byte | **-6.0%** |
| 152 Mbps | 8.92 ns/byte | 8.40 ns/byte | **-5.9%** |

Two independent pairs at different operating points agreeing to 0.1 points is much harder to explain
as noise than any single pair. It is also specific to the thread the change touches: across the
152 Mbps pair every other thread came in at **+0.8%** per byte, with the largest controls flat
(`MediaCodec_loop` -0.6%, `CodecLooper` -1.0%). In absolute terms the submit thread went from
170 to 160 ms/s at 152 Mbps, roughly 1% of one core.

**So the answer is device-dependent, and that is the finding.** No effect on the Shield (Tegra X1,
strong memory subsystem, effect below a ±10% resolution), a repeatable ~6% on the Homatics
(32-bit userspace on Amlogic S905X4). The copy costs real time on the weaker memory subsystem and
vanishes into the noise on the stronger one. Measuring only the Shield would have concluded the
change was worthless; measuring only the Homatics would have overstated it.

**Match the bitrate before comparing.** Per-byte cost is not scale-invariant - it fell from ~10.3 to
~8.9 ns/byte on the same build purely by going from 81 to 152 Mbps, as fixed per-frame overheads
amortise over more bytes. Only compare runs at similar Mbps, and record the Mbps with every result.

**Codec coverage on the Homatics.** All of the above is **H.264**. HEVC is reported broken on this
box, and AV1 could not be exercised: the box has `c2.amlogic.av1.decoder` in hardware, but the
Sunshine host could not encode AV1, so the client silently negotiated H.264 - confirmed by
`dumpsys media.metrics` showing `c2.amlogic.avc.decoder` instantiated and no `av01` instance. AV1
therefore remains the one untested risk class, and it is the one where **100% of bytes take the new
path**. It needs a host with an AV1-capable encoder.

**Identifying the submit thread: do not grep for `VideoRecv`.** It does not exist at runtime.
`callbacks.c:98` calls `AttachCurrentThread(JVM, &env, NULL)` with no `JavaVMAttachArgs`, so ART
assigns a default name *and renames the OS thread* on its first JNI call. It appears as an anonymous
`Thread-N` (`Thread-46` and `Thread-14` in the two runs here). Identify it by position instead: it
is the anonymous `Thread-N` whose TID falls between `Video - Rendere` and `VideoPing`, matching the
creation order in `VideoStream.c`.

```bash
PID=$(adb shell pidof com.limelight.unofficial | tr -d '\r')
adb shell "for t in /proc/$PID/task/*; do printf '%s %s %s\n' \$(basename \$t) \
  \"\$(cat \$t/comm)\" \$(cut -d' ' -f1 \$t/schedstat); done"
```

`schedstat` field 1 is `sum_exec_runtime` in ns and is readable from a plain `adb shell` even for a
non-debuggable release build, which is what makes this the only performance method available here.

### 6.7 Known gaps

- **ASan would not catch an overrun of the destination.** MediaCodec input buffers are allocated by
  the codec service and mapped in; they carry no ASan redzone, so a write past the end lands in
  adjacent mapped pages silently. ASan does still cover over-reads of the source `LENTRY` buffers,
  which are `malloc`'d by the depacketizer. Worth knowing before spending a day on it — 6.3's NPE
  detector and 6.5 cover the actual hazard better.
- **HWASan is unavailable on both devices.** It needs arm64 plus Android 14 or a HWASan system
  image: the Shield is arm64 but API 30 on a stock image, the Homatics is Android 14 but 32-bit.

---

## Hardware still needed

| Needed for | Hardware |
|---|---|
| §2 in full | Nintendo Switch Pro Controller + USB cable |
| §3 latency claim | Google TV Streamer, or another device with the AudioTrack fast-path bug |
| §3 surround | 5.1 or 7.1 output on the host |
| §4 pads | Xbox Series S/X pad, 8BitDo pad, PowerA Pro |
| §4 decoder | Amlogic-based Android TV device |
| §1 Win+Shift+Left | Dual-display host |
