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
- [ ] Setting on, **route change mid-stream**: unplug/replug HDMI, change HDMI mode, or switch
      audio output. Audio must recover, and the stream must not hang at "Waiting for audio
      stream establishment". Recovery happens natively — `errorCallback()` in
      `aaudio_renderer.c` reopens the stream on `AAUDIO_ERROR_DISCONNECTED` — so the pass
      condition is `Recovered AAudio stream after device disconnect` in logcat, with a sane
      frame buffer size on the line. **This is the check that matters most**, because if native
      recovery fails there is now no second line of defence: the session stays silent until the
      user turns the setting off. That is deliberate, but it makes this path the one that has
      to work.
- [ ] Setting on, **surround below Android 12L** (API 32) if such a device is available:
      must fall back to AudioTrack rather than opening a stream with an undefined layout.
- [ ] Under load — packet loss, decoder pressure — audio does not stutter. Two independent
      reports of unplayable stuttering exist against the implementation this replaces, so it
      is worth deliberately stressing.

> **Measured, and the Homatics is an affected device.** This file previously said the latency
> win was unconfirmed because no device on hand showed the symptom. That was an assumption, and
> it was wrong — it predates the granted-configuration readback, which disproves it outright.
>
> On the **Homatics Box R 4K**, AudioTrack is *denied* the fast path: AudioFlinger reports
> requested flags `00000004` against output flags `00000002`, and the track lands on Android's
> deep-buffer output — `PERFORMANCE_MODE_NONE`, a 1924-frame buffer, **169.6 ms** of measured
> output latency. AAudio on the same box, same stream, back to back, is granted `LOW_LATENCY`
> and `EXCLUSIVE` and measures **22.6 ms**. That is a 147 ms difference.
>
> On the **Shield TV** the picture is the opposite and stands on its own: AudioTrack *is*
> granted the fast path, the two paths differ by 2.5 ms, and AAudio costs two stream rebuilds
> and an order of magnitude more discarded audio for it. Keep the feature for the Homatics, not
> for that box.
>
> **The consequence, recorded rather than acted on:** `DEFAULT_ENABLE_AAUDIO` is false, so the
> Homatics currently ships with roughly 170 ms of audio latency by default, fixable only by a
> setting most users will never find. Both paths report their granted mode, so detecting the
> downgrade and switching automatically is possible rather than hypothetical. Not implemented —
> changing the default would make the Shield slightly worse, and picking per device needs more
> hardware time than has gone into it.
>
> Source: commit `810858bf` on `feature/audio-observability`, which also carries the readback
> instrumentation the measurement depends on. That instrumentation is **not** on master, so
> reproducing these numbers here means building that branch.

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

## 6. Input path: two-controller checks

Both items below are invisible with a single controller connected — that is the whole reason
they went unnoticed. Each needs a USB-driven pad (Switch Pro, or an Xbox pad on our own
driver) **and** an Android-enumerated pad connected at the same time.

- [ ] **Two pads, both sticks independent.** The scratch vector used for deadzone maths was
      shared across every controller while being written from two threads — the main thread for
      Android-enumerated pads, and the USB driver's reader thread for ours. The symptom is one
      pad's deflection perturbing the other's: hold the left stick hard over on pad A and work
      pad B's sticks, watching for A's reported position twitching. It is now one scratch vector
      per controller, so this should be clean.
- [ ] **Mouse emulation with two pads.** Enable mouse emulation on both and use them together.
      The mouse-emulation maths still shares one vector, which is safe only because that path
      runs entirely on the main thread — this is the check that says so.

---

## 7. USB controller motion is gated on what the host asked for

USB-driven pads used to report gyro and accelerometer on every input report, whether or not the
host had enabled those sensors — the Android sensor path avoids this for free by not registering
a listener until asked, but a USB driver parses motion out of every report it reads. It is now
gated on the rate the host requested.

The failure mode of a wrong gate is **silence, not an error**: motion simply stops reaching the
host, and nothing logs. So these need checking rather than assuming.

- [ ] **Gyro reaches a host that wants it.** With a pad that has one, in a game or test app that
      requests motion, confirm the host receives it. Check Sunshine's log for the enable request
      rather than judging by feel.
- [ ] **Motion stops when the host disables it**, rather than running for the rest of the session.
- [ ] **Motion survives an unplug/replug mid-stream.** This is the one most at risk. A reconnect
      builds a fresh `UsbDeviceContext` with both rates back at 0, so motion depends on the host
      re-enabling it in response to the controller-arrival event. `InputDeviceContext` carries its
      rates across a reconfiguration for exactly this reason; the USB path has no equivalent and
      leans on the protocol instead. If motion does not come back after a replug, that is what to
      fix — carry the rates across, keyed on the controller number.

---

## 8. Overlay composition

The performance and notification overlays used a translucent background (`#80000000`) over the
SurfaceView. A translucent layer above the video forces the hardware composer to blend, which on
this hardware means falling back to GPU (client) composition for the whole frame — so the
overlay changed the frame timing it exists to measure. They are now opaque.

**This is a hypothesis to confirm, not a fix to assume.** Whether the composer keeps the video
on its own plane is device-dependent.

With the stream running and the performance overlay **up**:

```bash
adb shell dumpsys SurfaceFlinger | grep -iE "composition|client|device"
```

- [ ] **Homatics Box R 4K:** the stream layer composites `DEVICE`, not `CLIENT`, with the
      overlay visible. Compare against the same command with the overlay hidden.
- [ ] **Shield TV:** same check.
- [ ] **The measurement moves.** Harvest `globalVideoStats` from the end-of-stream summary,
      overlay-on before and overlay-on after. If composition changed, frame timing with the
      overlay up should now be closer to overlay-off than it was.
- [ ] **Still legible over a bright scene.** Opaque black is a harder edge than the translucent
      box was.

> **If composition does not change on either box, revert it.** The readability cost is real and
> is not worth paying for a change that bought nothing. That outcome is a valid result, not a
> failed test — record it here so the idea is not retried blind.

---

## Hardware still needed

| Needed for | Hardware |
|---|---|
| §2 in full | Nintendo Switch Pro Controller + USB cable |
| §6 both items | A USB-driven pad *and* an Android-enumerated pad, connected together |
| §7 motion | A pad with a gyro (Switch Pro, DualSense, DualShock 4) + a host game that requests it |
| ~~§3 latency claim~~ | ~~A device with the AudioTrack fast-path bug~~ — met: the Homatics is one, measured at 169.6 ms vs 22.6 ms |
| §3 surround | 5.1 or 7.1 output on the host |
| §4 pads | Xbox Series S/X pad, 8BitDo pad, PowerA Pro |
| §4 decoder | Amlogic-based Android TV device |
| §1 Win+Shift+Left | Dual-display host |
