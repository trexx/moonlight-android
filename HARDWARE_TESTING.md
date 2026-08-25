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
- [x] **Unplug and replug mid-stream: the controller comes back, and the app does not leak the
      USB interface or crash.** *Verified on the Shield, 2026-08-19, cabled Xbox One pad, six
      cycles in one session - three with pad audio on.* Each one:

      ```
      Wired: device is gone; stopping audio
      USB device detached: /dev/bus/usb/001/0NN
      Removed controller: N
      Controller number 0 is now available
      Wired: device opened            (on replug)
      Assigned as controller 0
      ```

      The controller number returned to 0 every time, so nothing leaked - the number in
      `Removed controller: N` is the monotonic device id, which is meant to climb. With audio on,
      `Wired: audio stopped` and `Audio returned from the pads; local output reopened` followed
      without intervention. Errors were bounded at two to seven `LIBUSB_ERROR_NO_DEVICE` inside the
      same second as each unplug and then stopped: no spin. Crash buffer empty throughout.

      Before the detach handling this had *failed* -
      nothing noticed a cabled pad going away at all, so the claim, the controller number and the
      native driver all survived the unplug.

      - Unplug mid-stream with audio off. `USB device detached` must appear, then
        `Removed controller: N` and `Controller number N is now available`. Replug: input works.
      - Unplug mid-stream with **pad audio on**. The same, plus `Wired: device is gone; stopping
        audio` and no repeating USB errors after it, and the stream's audio returns to the TV.
      - **Repeat three or four times in one stream.** This is the leak check: controller numbers
        must not climb, and `Wired: device opened` must appear once per replug.
      - Unplug between streams, and with the app backgrounded.
      - Unplug the **dongle** mid-stream with a pad paired to it, for the parallel map.
      - `adb shell dumpsys dropbox --print data_app_crash` stays empty throughout. Tearing the
        driver down from its own read thread would be a self-join, and it would land here.
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
- [ ] **Continuous audio off: behaviour is unchanged.** *(Audio settings, off by default.)* The
      launch request must carry `continuousAudio=0` and the Sunshine log must not report the
      request. This is the check that matters — the default path stays exactly as it was.
- [ ] **Continuous audio on: the stream stays open through silence.** A **Windows** host then
      encodes silence rather than sending nothing while nothing is playing. Leave the host silent
      for 30 s, then resume audio: no gap, glitch or resync on the first sound back, and
      `Client requested continuous audio` in the Sunshine log from the connect. Linux and macOS
      hosts parse the flag and ignore it, so unchanged behaviour there is expected, not a failure.

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
- [ ] **`isExternal()` now trusts the platform.** The hardcoded overrides for Shield *Portable*,
      Tinker Board, Archos Gamepad 2, XPERIA Play and the Logitech G Cloud are gone, and that
      answer feeds two things. On the **Shield TV**: the remote's Back button must still leave the
      stream (`shouldIgnoreBack()`), and a paired controller must still report battery to the host
      (`LI_CCAP_BATTERY_STATE`). Check with the TV remote alone, and with a pad also connected.
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

## 5. Stream decryption (Mbed TLS PSA API)

Audio is the only stream encrypted with AES-CBC — video, control and RTSP all use AES-GCM.
When CBC breaks, the picture is perfect and only the sound disappears, with no error shown
to the user, so this needs checking explicitly rather than assuming "video works" means
crypto works.

Since the PSA migration (upstream `518b244`) every one of those paths was rewritten, not
just the CBC one, so this section now has to prove out GCM as well. Both directions matter:
GCM decrypt no longer shuffles the tag and ciphertext around in place, and GCM encrypt is
what carries Gen 7+ input to the host.

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
- [ ] **Keyboard and mouse input still reach the host.** Pre-Gen 7 hosts encrypt input with
      CBC, applying the caller's IV once and then chaining, and the PSA rewrite reproduces
      that by keeping one cipher operation alive across packets. Hosts new enough to use
      Gen 7+ input take the GCM branch instead and will not exercise it, so this only proves
      out against an older host.
- [ ] **Decrypt-failure counters read zero.** The end-of-stream summary now ends both RTP
      lines with a decrypt-failed count, and the overlay shows a line only when one is
      non-zero — so on a healthy stream that line should be *absent*, not zero. To prove the
      counter actually moves, remove `MBEDTLS_CIPHER_PADDING_PKCS7` from
      `moonlight_mbedtls_config.h`, rebuild, and confirm the audio count climbs.
- [ ] **Nothing fails only at stream start, and only sometimes.** PSA keeps its key slots in
      globals that each stream thread first touches on its own, which is why the config
      enables `MBEDTLS_THREADING_C`. A race there would not be a clean failure: it would be
      an occasional stream that decrypts nothing, or one stream of the several, and it would
      come and go between launches. Start and stop a stream ten or so times rather than
      trusting one long session, since a single good session says nothing about this.

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

## 6. Fixes ported from moonlight-vplus and xone

Four fixes re-implemented from the two more active forks. None is verifiable on CI — the
first needs a host and induced packet loss, the middle two need Xbox hardware, the last needs
a display running a fractional refresh rate.

### Decoder hang reporting

The wait for an input buffer was unbounded, so a genuine hang spun forever and was only ever
reported once the user quit — which also meant an ordinary quit after a few seconds of
backpressure recorded a decoder crash that never happened. The wait is now bounded at 5 s and
the shutdown path returns before the timing checks.

- [ ] **Quitting during heavy packet loss records no crash.** Induce sustained loss until the
      picture freezes, then quit from the game menu. Check
      `adb shell dumpsys dropbox --print data_app_crash` and confirm no new
      `DecoderHungException`. This is the false positive the fix removes.
- [ ] **A real hang is now reported while streaming.** Harder to provoke deliberately; if a
      decoder ever wedges mid-stream, the stream should end with a `DecoderHungException`
      after ~5 s rather than sitting on a frozen picture indefinitely. **This is a behaviour
      change** — streams that previously froze forever now terminate with an error.
- [ ] Ordinary stream exit is unaffected, and no `Dequeue input buffer ran long` lines appear
      during normal teardown.

### GIP rumble levels

Both Xbox rumble paths sent levels above the protocol maximum. MS-GIPUSB v20240916 §3.1.5.6.1
specifies every motor level as "Percentage, 0 – 100% (0x00 to 0x64)". The wireless path sent
up to 127; the wired path was worse, because `short` is signed in Java and `>> 9`
sign-extended — it climbed to 63 across the bottom half of the range, then jumped to 192 at
the midpoint and reached 255 at full strength.

What the firmware *did* with those out-of-range values is unverified. Clamping is the likely
behaviour, which would mean the wired pad was effectively at full rumble for anything above
half strength.

- [ ] **Wired Xbox One/Series pad: rumble still reaches full strength**, and no longer jolts
      at the halfway point of a ramp. A game with variable force feedback (racing games are
      easiest) shows this best.
- [ ] **Wireless pad via the adapter: same check.** It had no discontinuity, only a ceiling
      27% too high, so expect a subtler change than the wired path.
- [ ] **Wired and wireless now feel the same** at the same in-game intensity. They did not
      before, and that is the clearest single check that both paths are right.
- [ ] Trigger rumble (impulse motors) tracks the same way on a pad that has them.

### Duplicate controller over the wireless adapter

A retransmitted association request allocated a second WCID and built a second `Controller`
for one physical pad, so it could appear twice in Moonlight with only one copy receiving
input, and repeated retries could exhaust all 16 slots.

- [ ] **Pair a controller and confirm exactly one appears.** Repeat several times, and try to
      provoke a retransmission by powering the pad on at the edge of adapter range.
- [ ] **Disconnect and reconnect the same pad.** It must associate again — the address slot is
      cleared on disconnect, and getting that wrong would lock the pad out until replug.
- [ ] Four pads still pair and work simultaneously.

### Display refresh rate

`setClientRefreshRateX100` truncated instead of rounding, sending 5993 for a 59.94 Hz mode.

- [ ] On a display running **59.94 Hz** (or 23.976/29.97), confirm the host is told 5994 and
      that pacing is unchanged or better. Harvest `globalVideoStats` from the end-of-stream
      summary rather than reading the overlay, and compare overlay-on with overlay-on.

---

## 7. GIP driver defects found against the published spec

Audited against [MS-GIPUSB] v20240916. The wire protocol was sound; these sit above it. **The
first item gates the rumble work in §6** — until now the host was told these pads had no
capabilities at all, so it had no reason to send rumble of either kind.

### The wireless pad now announces itself

`XboxWirelessController` never set `type` or `capabilities`, so the host received
`LI_CTYPE_UNKNOWN` with a zero capability bitfield. Separately, no USB-driver controller ever
set `supportedButtonFlags` — `AbstractXboxController` was assigning its button set to the
live-state field instead — so every one of them also advertised zero buttons.

- [ ] **Rumble arrives at a pad on the wireless adapter at all.** If it already did before this
      change, then the capability was not gating it after all — worth knowing, and worth saying
      so here. If it did not, this is what fixed it, and §6's rumble scaling gets its first real
      exercise.
- [ ] **Trigger rumble arrives** on a pad with impulse triggers. This has almost certainly never
      worked over the adapter, since `LI_CCAP_TRIGGER_RUMBLE` was never advertised.
- [ ] **Wired pads still rumble** — `AbstractXboxController` changed too, so this is the
      regression check.
- [ ] **The host shows the pad as an Xbox controller**, with Xbox glyphs rather than generic ones.
- [ ] **No spurious button press at pairing.** The old code seeded live state with every button
      set, so watch the first moment after a pad connects.

### Battery reaches the host

Battery was parsed natively and thrown away; there was also no callback on `UsbDriverListener`
to carry it. Both are now in place and `LI_CCAP_BATTERY_STATE` is advertised.

- [ ] **Battery level appears host-side** and tracks a real discharge over a session.
- [ ] **Charging is reported while a play-and-charge kit is charging.** The charge state is bits
      5:4 of the status byte (MS-GIPUSB Table 30), decoded now rather than discarded.
- [ ] **A pad with no battery at all**, running off a plain USB cable, reports "not charging"
      with an unknown percentage rather than a wrong number.
- [ ] **The reported percentage tracks reality.** The four levels map to 10/25/50/100, which are
      the spec's own figures rather than invented midpoints — level 01 is defined as
      "approximately 25% charge remaining".
- [ ] **Test on a Series X|S pad specifically.** Those are the ones expected to send the extended
      status message that was previously dropped whole; if battery works there, that fix is
      confirmed too.

### JNI on the input path

The read thread now attaches to the JVM once instead of per input report, and the callback class
and methods are resolved once at registration.

- [ ] **Sustained play on four pads at once** with no dropped, laggy or duplicated input.
- [ ] **No `local reference table overflow` in logcat**, and no crash over a long session. This is
      the specific failure mode if a local reference is left unreleased now that the per-call
      detach no longer frees them. Clear `persist.log.tag` on the Homatics first, and restore `S`
      after.
- [ ] **Pair and unpair repeatedly**, then confirm controllers still arrive and are removed
      cleanly — the attach/detach rework touched those paths too.

---

## 8. GIP metadata (diagnostic)

The driver now reassembles fragmented GIP messages and asks each controller for its metadata.
**Nothing acts on the answer yet** — it is logged so that later decisions can be made from what
the hardware reports rather than from what the driver assumes.

This touches the packet parser every controller input flows through, so the regression checks
matter more than the new output. Fragmented messages take a separate branch and the unfragmented
path is unchanged, but that is the thing to confirm.

**When the request is sent matters as much as what it parses.** The handshake at the top of
`gip.h` puts Identify immediately after the announce, before power mode. Asked later — once the
pad has powered on and started its data classes — the pad answers with its metadata in full and
then stops sending input (`0x20`) and status (`0x03`) for the rest of the connection.

The failure reads as anything but a handshake bug: protocol-control messages keep arriving, so
the guide button still works and the pad still shows as connected, while every stick, button,
trigger and the battery are dead. Confirming a pad connects is therefore not enough — press
something other than the Xbox button.

- [x] **All controller input still works** — buttons, sticks, triggers, guide, rumble. This is the
      regression check, and it caught a real one, though not the parser mistake it was written to
      guard against: the reassembly was correct and the request was simply sent too late.
      *Verified on the NVIDIA Shield TV, Xbox One pad (PID `02dd`) on adapter `045e:02fe`. Input
      reports reaching the driver: 1 in 39 s with the request sent last, 1621 in 53 s with it
      removed entirely, 326 in 38 s with it moved ahead of power mode — the last of those with
      metadata and `Battery: medium, not charging` both arriving as well.*
- [ ] **`Metadata received: N bytes` appears** in logcat shortly after a pad connects, followed by
      one or more `Metadata <element>` lines. Clear `persist.log.tag` on the Homatics first, and
      restore `S` after.
- [ ] **Record the `audio formats` line verbatim** for each pad tested, in the table below. Two
      bytes per entry. This is the specific thing the controller-audio question needs: if a pad
      reports a 48 kHz format, Moonlight's decoded audio would need no resampling to reach it.
- [x] **A cabled pad's USB descriptors are logged**, so whether a given pad has the audio interface
      of MS-GIPUSB 2.2.12 can be answered by plugging it in rather than inferred. Debug builds only,
      logged for every enumerated device including ones the driver declines to claim, from both the
      attach broadcast and the startup enumeration. Note `UsbDriverService` only runs while a stream
      is bound, so plug the pad in and then start a stream, or plug it in while already streaming.
      *Xbox One pad (PID `02dd`, model 1697) cabled to the Homatics Box R 4K Plus: five interfaces,
      with `interface 1 alt 1` carrying isochronous endpoints `0x02`/`0x82` at 228 bytes and 1 ms -
      the audio interface, exactly as specified. Its three interfaces are the three sub-devices of
      Table 1: primary, 3.5 mm audio, other.*
- [ ] **Sanity-check the element parse before trusting anything in that table.** The same Shield
      run reported `Metadata commands: 2 item(s): 17 00`, and a `firmware versions` dump whose
      bytes visibly ran on into the elements printed after it — so the offsets or item widths in
      `IdentifyData` are wrong for at least that pad. Until that is settled an `audio formats`
      line may be reading the wrong bytes entirely, and a 48 kHz answer cannot be believed.

      *Corroborating but not closing, 2026-08-24:* the Elite Series 2 parses cleanly with the
      corrected widths — `Metadata commands: 6 item(s): 20(len 47) 0c(len 17) 09(len 60) 4d(len 64)
      1e(len 64) 0e(len 9)`, plausible lengths throughout, and `firmware versions: 05 00 17 00`
      matching the 5.23 the announce reports. That is a different pad and a different run from the
      one that misparsed, so it does not settle the original complaint.
- [ ] **Two sub-devices answering fragmented metadata at once are reassembled separately.**
      *Never reproduced — the fix is preventive and is not to be recorded as verified.*

      Reassembly was one buffer shared across every device id on a pad, so two fragmented transfers
      in flight together interleaved into it. Nothing reported it: the guard compares the message
      command and both are `CMD_IDENTIFY`, so what came out was one plausible-looking metadata blob
      built from two devices.

      **Neither pad on hand can show it**, and the reason is worth keeping because it explains why
      this survived so long:

      | Pad | Answers the probe on id 1? | Audio metadata |
      |---|---|---|
      | Elite Series 2 `0b00` | yes, fragmented | id 2, **unfragmented** (110 bytes) |
      | Xbox One `02dd` | no — nothing answers the probe | id 3, **fragmented** |

      A pad with both would collide. It was close on the Elite: device 1's transfer completed and
      device 2 announced within the same millisecond.

      To attempt it: debug build, pad powered on with a headset already inserted so its audio
      sub-device is present when `probeSubDevices()` fires, and watch for two chunk streams
      interleaving — `Accessory chunk: #n id=X` with X changing mid-transfer. Strictly sequential
      per id means not reproduced, which is the result so far on both pads.
- [ ] **A sub-device's input is not taken as the pad's.** Debug builds log
      `Ignoring input from sub-device N`. **Expect this to stay silent on both pads** — neither
      declares input (`0x20`) in a sub-device's capabilities — so an empty log is the pass, and a
      line appearing means a pad with a chatpad-like sub-device has turned up and is worth
      recording. The same guard covers the guide button, the serial number and audio samples.
- [ ] **A pad leading with a format other than 48 kHz stereo.** The audio menu now answers from the
      *first* advertised pair, because that is the one 2.2.11 has the host propose. No pad seen
      here leads with anything else — both advertise `09 10` — so the refusal branch is
      unexercised, and it logs `leads with format XX/YY, not 48 kHz stereo` rather than declining
      in silence. If that line ever appears, the question is whether the "first configuration" rule
      is really absolute.
- [ ] **No `Malformed chunk`, `Truncated chunk` or `Chunk overruns` lines** in normal operation.
      Occasional ones during pairing are worth noting rather than ignoring.
- [x] **Accessory clients are reported rather than dropped in silence.** Debug builds log every
      packet addressed to a device id above zero, at the parser's accessory filter, at the
      fragmented-message branch that runs ahead of it, and at the wcid lookup above both. Confirm
      `Accessory diagnostics compiled in` appears once per pad connect first — without it an empty
      result means nothing, since it cannot be told from the diagnostics being compiled out.
      *Xbox One pad (PID `02dd`) on adapter `045e:02fe`, Shield TV: zero accessory packets with the
      headset connected before power-on, zero when hot-plugged mid-stream, and zero across 24 s of
      audio actually being transmitted.*

      **Since explained, and no longer a mystery.** Those zeroes were correct and their cause was
      upstream: [MS-GIPUSB] §2.2.1.4 only enumerates sub-devices after a successful security
      handshake, and this driver had never authenticated. With the handshake implemented (§9) the
      same pad announces `dev=3` within a second of initialising. The diagnostics are still worth
      keeping — they are what proved the absence was real rather than a parser fault, and they are
      how a pad that announces something unexpected would be spotted.
- [ ] **Nothing regresses if metadata never arrives.** A pad that does not answer should still work
      exactly as before — the request failing is logged and otherwise ignored.

| Pad | Firmware | `audio formats` bytes | Notes |
|---|---|---|---|
| Xbox Elite Series 2 `045e:0b00`, device 0 | 5.23.6.0 | *(none declared)* | A pad declares no audio formats of its own and is not supposed to — audio is a separate GIP device with its own metadata |
| its 3.5 mm audio sub-device, id 2, `045e:0b01` | 5.23 | `09 10 09 09` | 48 kHz stereo render (`0x10`) in the first pair, so no resampling. Interface GUID `bc25d1a3-c24e-4992-9dda-ef4f123ef5dc` (IHeadset). Metadata is 110 bytes and arrives **unfragmented** — see section 10 |

---

## 9. GIP security handshake

The driver now performs the [MS-GIPUSB] security exchange when a pad connects. **This is not a
security feature** — the certificate is never validated and the link is still unencrypted. It is
here because §2.2.1.4 makes it the gate on everything else: *"GIP supports enumeration of additional
sub-devices after the primary device has completed the Security Handshake successfully."* No
handshake, no sub-devices, and therefore no headphone audio.

**Both versions are implemented.** v1 (RSA, commands `0x01`–`0x08`) and v2 (ECDH P-256,
`0x21`–`0x27`). The host always opens with a v1 hello; a device that wants v2 answers stating so in
its data header, and the exchange restarts from a fresh transcript. v2 is unauthenticated — the
certificate is requested and hashed in, but its key is never used — and so, in practice, is v1,
where the certificate is never validated either.

**This runs on every pad connect, whether or not audio is wanted**, so the regression checks matter
far more than the feature check. Two connect-loops were inflicted on a real pad while getting the
fragmentation right, so the failure mode is known and unpleasant: the pad cycles connect/disconnect,
its light flashing, with the add/remove device sounds looping.

- [x] **Input works after the handshake.** Buttons, sticks and triggers — not just the guide button,
      which kept working through an earlier regression where everything else was dead (§8).
      *Verified repeatedly on the Shield TV, Xbox One pad (PID `02dd`) on adapter `045e:02fe`.*
- [x] **The pad connects and stays connected**, with no add/remove sound looping and no flashing
      light. *Stable across many sessions after the fragmentation fix.*
- [x] **The exchange completes**, logged as `Security: command 0x…` per message. The sizes to expect
      on a v1 pad, matching the Windows capture:

      | Message | Bytes |
      |---|---|
      | `HOST_HELLO` acknowledged | 6 |
      | `CLIENT_HELLO` | 90 |
      | certificate, reassembled | 825 |

- [x] **A sub-device announces afterwards.** `dev=3` (VID `045e`, PID `02e4`) appears within about a
      second of the pad initialising — the 500–1000 ms §2.2.11 describes. Before the handshake
      existed this never happened under any condition (§8).
- [ ] **A pad that never completes the handshake still works for input.** Pull the batteries mid
      exchange, or test a pad that wants v2. Authentication failing must degrade to "no audio",
      never to "no pad".
- [ ] **Repeated connect/disconnect cycles**, ten or more, with no leak of the per-pad security
      state and no slot exhaustion. Each connect runs a fresh exchange.
- [ ] **Four pads on one adapter** authenticate independently and all four keep working. Each has
      its own sequence pool; a shared counter would show up here and nowhere else.
- [x] **A v2 pad completes the ECDH exchange.** *Verified on the Shield TV, 2026-08-24, with an
      **Xbox Elite Wireless Controller Series 2** (`045e:0b00`, firmware 5.23.6.0, hardware 5.6) on
      adapter `045e:02fe`.* Reproduced across four connects. The pad announces security version 1.0
      in its hello and then asks for v2 anyway, which is why the announce cannot be used to
      discriminate:

      ```
      Security: command 0x22, error 0x00, 90 bytes    ← v1 hello answered with a v2 reply
      Security: device wants protocol v2, restarting the exchange
      Security: command 0x22, 182 bytes
      Security: command 0x23, 778 bytes
      Security: command 0x24, 138 bytes
      Security: command 0x27, 74 bytes
      Security: handshake complete
      ```

      **The request lengths taken from xone's structures were all correct**, which is worth
      recording because they were flagged here as the most likely thing to be wrong. Payload sizes
      answered exactly as predicted; the byte counts logged above are those plus the handshake and
      data headers.

      | Message | Payload predicted | Answered |
      |---|---|---|
      | `CLIENT_HELLO` (0x22) | 172 | 172 |
      | `CLIENT_CERTIFICATE` (0x23) | 768 | 768 |
      | `CLIENT_PUBKEY` (0x24) | 128 | 128 |
      | `CLIENT_FINISH` (0x27) | 64 | 64 |

- [x] **A v1 pad still authenticates with the v2 branch merged.** The upgrade path shares
      `sendAuthPacket` and the version check with v1, so this is the regression that matters more
      than the feature. *Verified on the Shield TV, 2026-08-24, Xbox One pad `045e:02dd` on adapter
      `045e:02fe`, on the same build that completes the v2 exchange.*

      ```
      Device 0 announced, vendor 045e product 02dd
      Security: command 0x02, error 0x00,  90 bytes    ← v1, no upgrade triggered
      Security: command 0x03, error 0x00, 825 bytes
      Security: command 0x08, error 0x00,  74 bytes
      Security: handshake complete
      Device 3 announced, vendor 045e product 02e4
      ```

      Byte counts match the v1 baseline recorded above exactly (90 / 825 / 74), the version check
      never fired, and headphone audio and input both worked on that pad afterwards.
- [x] **The transcript is reset on upgrade.** *Confirmed by implication on the same runs.* If it
      were not, everything would succeed until the final finish messages, which would then
      disagree — and the device accepted `CLIENT_FINISH` rather than stopping there. That remains
      the first place to look for a v2 handshake that reaches `CLIENT_FINISH` and halts.
- [ ] **Timing on a cold boot.** The handshake is sent at the end of `startDevice()`, behind the
      metadata response with a 500 ms fallback. Confirm a pad powered on *before* the adapter, and
      one powered on long after, both authenticate.

Link encryption is deliberately **not** implemented. xone programs a per-client key into the MT76
from the derived session key; the working assumption here was that a pad might withhold audio until
the link was encrypted, and that turned out to be false. `authCompleted()` exists as an unused hook
if it is ever needed. Leaving it out also avoids its failure mode, which is severe: a wrong key
silences the pad completely, input included.

---

## 10. Audio to the controller's headphone jack

Off until switched on from the in-game menu, **Controller headphone audio**, which only appears
when an adapter is running, a pad is paired, and the stream's audio is 48 kHz stereo. Up to two
pads at once; while any pad is on, the TV gets nothing.

**This works**, on two pads now. An Xbox One pad (PID `02dd`) and an **Xbox Elite Series 2**
(PID `0b00`), both over adapter `045e:02fe` on the Shield TV, play the stream's audio through their
headphone jacks, and the TV falls silent while they do. It took the security handshake in §9 to get
there — audio is a sub-device, and sub-devices do not appear until the pad has authenticated.
Details in `app/src/main/jni/xow_driver/AUDIO.md`.

**The Elite took one more fix, and it is the reason to test a second pad at all.** Its audio
sub-device answers metadata in 110 bytes, which fits the Command data class MTU and so arrives
*unfragmented*. `handlePacket()` only dispatched the fragmented form, so the reply was acknowledged
and then dropped, the sub-device was never adopted, and the menu read
`Unavailable (no headset detected)` with the headset plugged in and `Device 2 announced` in the log.
Fragmentation follows size, not message type (2.2.10.4); the two pads sit either side of the MTU
and nothing else about them differs here.

The first two items are the ones that decide whether this ships at all, and **the second is still
open** — the feature works but has not been shown to be worth using.

- [x] **A second pad's audio sub-device is reached**, not just the one the path was built on.
      *Xbox Elite Series 2 on the Shield TV, 2026-08-24.* The full sequence, which matches the
      Windows capture in `AUDIO.md` step for step:

      ```
      Security: handshake complete
      Device 2 announced, vendor 045e product 0b01     ← 3.5 mm audio, spec Table 1's own example
      Metadata from device 2: 94 bytes                 ← 110 on the wire, unfragmented
      Audio device 2 offers 2 format pair(s), first 09/10, announced yes
      Audio: proposed format 09/10 to device 2, awaiting its reply
      Audio: device confirmed format 09/10, starting it
      Audio: device volume, speaker 50% (writable), balance 50, mic 100%, flags 0x84
      Audio: device reported volume, streaming
      ```

      Two things worth carrying forward. The audio device announces on **expansion index 2** while
      carrying PID `0b01`, which [MS-GIPUSB] Table 1 assigns to sub-device *1* of this exact pad —
      so the announced index is what to address, never the PID's implied one. And it announces only
      when a headset is inserted: `probeSubDevices()` at handshake completion finds nothing on
      index 2 until then, which is hot-swap working as §1.2 describes rather than a fault.
- [x] **No pad enabled: nothing changes.** Audio behaves exactly as before, no new logging, and
      the menu entry is the only visible difference. This is what makes the feature safe to have.
      *An empty target list is the normal path and is checked with one volatile read per audio
      frame; the overlay line is likewise absent entirely rather than reading zero.*
- [ ] **Input latency, audio off vs one pad vs two pads.** Audio puts ~192 KB/s per pad onto the
      same 2.4 GHz link the controller input uses. xone gives audio its own hardware queue, which
      *suggests* the radio prioritises input, but that is inference. **If input latency worsens
      measurably, this feature is not worth using** — record the numbers either way. Take them
      from the end-of-stream summary, not the overlay, and compare like with like.
- [x] **Audio is audible in the pad's headphones**, at the right pitch and speed. Wrong pitch
      means the negotiated format and what is being sent disagree.
      *Verified on the Shield TV, Xbox One pad (PID `02dd`) on adapter `045e:02fe`, 4K60 stream.
      Format `09/10`, negotiated from the sub-device's first advertised pair rather than assumed.
      Sender held 125.04 packets/s against an expected 125.00 over 102 s.*
- [ ] **Two pads at once**, both correct. A third shows "Off (two controllers already)" and
      refuses rather than silently doing nothing.
- [x] **A pad keeps its number across a disconnect.** *Verified on the Shield TV, 2026-08-24.*
      Numbering was the adapter slot, and `Mt76::associateClient()` hands out the *lowest free* one
      — so cabling a pad freed slot 1, a second pad took it, and the first came back as 2. Pads are
      now numbered by their wireless address, assigned on first sight and kept for the life of the
      adapter.

      Reached further than the menu label: the number is namespaced into the controller ID, which
      is the key `ControllerHandler` stores a pad's context under, so a returning pad now keeps its
      player number instead of being treated as a new controller.

      To re-test: connect A, connect B, power A off, power A on. A must return to its original
      number rather than taking B's.

      **A pad that leaves for good does not strand its player slot, and this is the question that
      will be asked again.** Three different numbers are in play and only one of them is sticky:

      | Number | Assigned by | Released on removal? |
      |---|---|---|
      | Player number, sent to the host | `ControllerHandler.assignControllerNumberIfNeeded()` | **Yes** — `releaseControllerNumber()` clears the bit, and the next pad to send input takes the lowest free one |
      | This menu's label | `GameMenu.showPadAudioMenu()`, positional | N/A — a departed pad leaves no hole |
      | Pad identity | `XboxWirelessDongle.numberFor()` | **No, deliberately** — releasing it is what would break reconnect stability |
- [x] **A headset pulled from a pad reverts the menu**, rather than leaving it offering `Off` for an
      empty jack. *Verified on the Shield TV, 2026-08-24, both pads, with audio off, with audio on,
      and across a replug.*

      The pad reports it — a Status message addressed to the *audio sub-device*, with Power level
      `00` per Table 30. `statusReceived()` took the device id and never read it, so the report was
      applied to the primary pad. Two symptoms, and the second was never reported because it
      self-corrected:

      ```
      Accessory packet: #5 id=3 cmd=03 ty=2 len=4 size=8
      Battery: absent, running on external power     ← the pad's, and wrong
      Controller is powering off or resetting        ← the pad's, and wrong
      ```

      Those two were pushed to the host as the pad's battery state. **So check battery reporting on
      the host while plugging and unplugging a headset** — it must not flicker to absent. Expect
      `Audio device N reports powering off; forgetting it` and no battery line at all.
- [x] **A headset pulled mid-stream returns audio to the TV** without stalling the stream or
      leaking the sender thread. *Verified on the Shield, 2026-08-24: `Audio session: 535 packets
      sent, 1536 bytes dropped, 0 send failures, 0 underruns` then `Audio disabled for controller`,
      on the teardown thread rather than the driver's read thread.*

      The single dropped buffer is the one in flight when the jack was pulled, and is expected.
      This is a near-rehearsal of the pad-powering-off row below, which is still open — the
      difference is that there the whole pad goes away, not just its audio device.
- [x] **Toggling mid-game** moves audio between the TV and a pad promptly, both directions,
      repeatedly. The toggle runs off the main thread — watch for any UI stall regardless, since
      disabling joins a sender thread that may be inside a USB write with a one-second timeout.
      *Verified both directions across several sessions on the Shield; the TV mutes on enable and
      returns on disable, with no observed stall.*
- [ ] **A pad powering off mid-session** returns audio to the TV, does not silence the stream,
      and does not wedge or leak its sender thread.
- [x] **Input still works on a pad that is streaming audio.** It shares the link and the GIP
      command path with audio, which is much the larger traffic.
      *Verified on every build in this series — buttons, sticks and triggers, not just the guide
      button, per the warning in §8.*
- [ ] **Rumble still works on a pad that is streaming audio.** Not separately checked; input was
      the regression this series kept producing, and rumble was never exercised with audio on.
- [ ] **No pops, clicks or drift over a long session.** Sends are a fixed 1536 bytes and the pad's
      requested flow rate is ignored — and per MS-GIPUSB 3.2.5.1.5 honouring it *is* the mechanism
      that eliminates pops and clicks, so this is the item most likely to fail. If anything is
      heard, that is the fix to reach for.
      *Nothing audible across sessions of up to ~100 s, but that is far short of "long". The
      counters give an objective proxy while listening: 0 dropped and 0 send failures means
      nothing was discarded or refused.*
- [x] **Record what flow rate the pad actually asks for**, in the table below — it was unverified
      for this transport. The spec's examples are per-1 ms USB message (192 bytes for 48 kHz
      stereo) while we send one 8 ms message of 1536, and xone assumes the whole-buffer figure.
      *Answered: **1536**, so the units are whole-buffer and xone's assumption is right. Steady
      throughout, never straying outside the ±32-byte band the device is permitted to modulate
      within.* To see every value rather than only outliers, lower `AUDIO_FLOW_RATE_TOLERANCE` to
      0 in a debug build.
- [x] **The session's health is measurable without a listener.** Four counters — packets sent,
      bytes dropped, packets late by more than 12 ms, send failures — plus the last flow rate, on
      the performance overlay while audio runs and in a summary line when it stops.
      *Reference session: 12816 packets over 102.5 s (125.04/s vs 125.00 expected), 0 dropped,
      0 failed, 179 late (1.4%).* Some lates are structural, not a fault: the host feeds 960 bytes
      every 5 ms and the pad drains 1536 every 8 ms, so the sender's wait alternates 5/10 ms and
      the 10 ms half needs only 2 ms of jitter to cross. **If a stream ever negotiates 10 ms Opus
      frames, expect the late count to rise sharply with no change in audio quality** — four
      packets in five then wait a full 10 ms. Read the count against the frame duration, never on
      its own.

| Pad | Nominal flow rate reported | Range observed | Pops/clicks? |
|---|---|---|---|
| Xbox One, PID `02dd`, adapter `045e:02fe` (Shield TV) | 1536 | no excursions logged | none heard, ~100 s |
- [x] **Headphone volume can be changed from the menu**, and the level shown is the one actually
      in force. Needed because pad audio bypasses AudioTrack and AAudio, so Android's volume and
      the TV remote do not reach it, and a pad with an integrated jack has no volume buttons.
      *Xbox One pad (PID `02dd`) reports `speaker 80% (writable), balance 50, mic 100%, flags
      0x84` — so it comes up at 80, not full scale, and 0x84 is writable plus headset-detected.
      The speaker field being writable means the device does the attenuation itself and the
      software scaling fallback never runs here.*

      The first version of this displayed a nominal 100% before anything had been sent, so
      selecting "100%" raised a volume already shown as 100. **Check the displayed figure against
      the `device volume` log line, not just that the control works.**
- [ ] **A pad that flags its speaker volume read-only** falls back to software scaling. No such
      pad has been seen — this one is writable — so that path is unexercised.
- [ ] **Volume changed on the device itself** is picked up. 3.2.5.1.1 has the device re-send the
      volume message whenever a field changes; nothing here has a device-side control to try it
      with.
- [ ] **A surround stream hides the menu entry** rather than offering something that would send
      6-channel audio to a stereo device.
- [x] **A cabled pad reaches the audio sub-device.** The full GIP stack runs over interface 0's
      interrupt endpoints and the handshake completes, exactly as it does over the adapter.
      *Xbox One pad cabled to the Shield TV, with the wired GIP driver enabled: metadata from
      device 0 (198 bytes), handshake at the same sizes as the wireless exchange and the Windows
      capture (90 / 825 / 74), then `Device 3 announced, vendor 045e product 02e4` with interface
      GUID `bc25d1a3-c24e-4992-9dda-ef4f123ef5dc` (IHeadset) and audio formats `09 10 09 09`.*

      Two things this settled that were open. The pad **answers a metadata request from the Active
      state**, which 3.1.1 says SHOULD NOT happen - so no Set Device State: START is needed to
      wake it. And **it never sends a Hello to us at all**, because Android's driver already took
      it through Arrival; everything has to be started by hand.

      **Do not reset the device to force a Hello.** Set Device State: RESET takes the pad off the
      USB bus - the read fails with `LIBUSB_ERROR_NO_DEVICE`, Android re-attaches it, and the
      permission prompt loops. Seven rounds were inflicted on a real device before this was
      understood. xone's `usb_reset_device()` is the same trap by a different route.
- [x] **A cabled pad's endpoints are read from it, not assumed.** *Both pads verified on the
      Shield TV, 2026-08-24.* An Elite Series 2 could not be driven over USB at all before this and
      took input down with it: every transfer failed `LIBUSB_ERROR_IO` in the same millisecond as
      the open, because the addresses were hardcoded to the Xbox One pad's and the Elite's are one
      higher throughout.

      | Interface | Xbox One `02dd` | Elite Series 2 `0b00` |
      |---|---|---|
      | 0 — GIP | `01`/`81` intr | `02`/`82` intr |
      | 1 alt 1 — audio | `02`/`82` ISOC, 228 both ways | `03`/`83` ISOC, 228 out / **64 in** |
      | 2 alt 1 | `03`/`83` bulk | `04`/`84` bulk |

      Confirm the line `Wired: GIP on 02/82, audio on 03/83 (228/64 bytes)` names the pad in front
      of you. The Elite is the one that matches 2.2.12 — 228 out and 64 in — and `02dd`'s 228 both
      ways is the outlier, so neither pad is the "normal" one to generalise from.

      **The old failure mode is worth recognising**, because it looks like a dead pad rather than a
      driver fault: the claim succeeds, Android's driver has already been detached from interface
      0, and then nothing drives the pad. A device whose interface 0 yields no interrupt pair is now
      refused instead, which releases the interface and lets Android take it back — no headphone
      audio, but input intact.
- [ ] **A pad whose interface 0 has no interrupt pair is refused, and input still works.** The
      refusal path above. Neither pad on hand exercises it, so this is unverified rather than
      passing.
- [x] **Audio reaches a cabled pad's headphones**, over isochronous transfers on interface 1 alt 1.
      *Xbox One pad cabled to the Shield TV: 21900 packets over 87 s - 251.7/s against an expected
      250.0 - with 0 dropped, 0 send failures and **0 underruns**. Volume works too, through the
      same code as the adapter: the pad reports `speaker 80% (writable) ... flags 0x84`, which is
      writable plus headset-detected.*

      **16 ms of queued audio is enough** - four isochronous transfers of four packets, against
      xone's 96 ms. Zero underruns across the session says the tight depth is real rather than
      hopeful. Isochronous reports a status per packet, so raise it only against a measured count,
      never on suspicion.

      Flow rate reads 0 on a cable and that is expected: it arrives on Audio Capture messages,
      which come in on the isochronous IN endpoint that nothing here reads.
- [x] **Enabling and disabling repeatedly stays clean**, without unplugging or restarting.
      **The device is configured once and never renegotiated** - this is the rule to keep. 2.2.11
      has audio flow continually "even if the data represents only silence", and xone configures at
      `gip_headset_probe()` and never again for the life of the client. A per-session
      stop/configure/start cycle degrades the pad a step each time: first session clean, each one
      after it worse, cleared only by unplugging - while our own side measures perfect throughout.
      *If cabled audio ever starts degrading across sessions again, look here first.*
- [!] **A pad left streaming by a killed process plays degraded until the cable is pulled.**
      Understood rather than mysterious, and avoided entirely by exiting cleanly - disable audio,
      or disconnect from the menu, before closing.

      **Part of what was attributed here was ours.** Degraded audio on every session after the
      first had a second cause entirely on the host side - the ring's cushion was built once and
      never rebuilt - and that one is fixed; see the re-priming check below. What remains device-side
      is what was seen *before audio was ever enabled* in a new process: the previous run's stream
      still playing, heard as repeating noise the moment a stream started. Only that part needs the
      cable pulled. Re-test this row after the re-priming fix rather than assuming it is unchanged.

      2.2.11 keeps a started audio device streaming until it is powered off, disconnected or told
      to stop, and Android closes the USB connection before any teardown of ours runs, so the stop
      fails with `NO_DEVICE`. Every recovery available to a *new* process was tried on hardware:

      | Attempt | Result |
      |---|---|
      | Set Device State: STOP on discovery and on teardown | no effect |
      | Set Device State: RESET to the audio sub-device | no effect |
      | Proposing the other advertised format first, so the real one is a change | ran, no effect |
      | Set Device State: RESET to the primary device | pad leaves the USB bus, permission prompt loop |
      | `libusb_reset_device()` re-enumeration, as xone does at probe | pad unclaimed, **input dead**, USB stack cycling |

      **Do not reach for re-enumeration again without reading that last row.** It is what unplugging
      does and what the reference implementation does at every probe, and through a wrapped
      descriptor on Android it is not equivalent - it cost input, which is the product.
      `XboxWiredGipController.resetIfPreviousSessionUnclean()` is kept unused as the record.
- [x] **Streaming is unaffected by cabled pad audio.** Measured from the end-of-stream video
      totals rather than the overlay, which forces GPU composition on this hardware.
      *Two runs on the Shield with a cabled pad taking audio throughout: 2138 frames received /
      2137 rendered, and 4182 / 4182, both with **zero frames lost in zero loss events**, 1 ms
      end-to-end and 0 ms decoder latency. Zero loss is the floor, so an audio-off baseline cannot
      be better - the question is answered without one.*

      The cost that exists is USB and CPU, not frame timing: while a cabled pad has audio
      configured, 1000 isochronous packets a second go out and 1000 come in, with ~500 transfer
      completions and up to 500 event-thread wakeups a second. It runs for the life of the
      connection once audio has been enabled once, because the stream deliberately stays up
      carrying silence. None of it exists with the preference off.
- [!] **Seen once, not reproducible: two controllers reporting audio sessions from one cabled pad,
      with corrupted audio.** One ran clean at 192.3 bytes per packet over isochronous; the other
      discarded 65% of what it was given - 3874176 bytes of 5925120 - with 24 underruns.

      The mechanism is clear even though the cause is not. A controller with no transport sends GIP
      audio down the main link, which on a cable is interface 0's interrupt endpoint: 16 KB/s
      against the 192 KB/s audio needs. So it dropped what it could not send and wrote the rest
      into the same GIP link the isochronous stream was using - two writers, one device.

      Most likely a leftover of the withdrawn re-enumeration path, which built a controller and
      then reset the device out from under it, and of the duplicate-claim guard landing later than
      it should have. Both are now in place and it has not recurred.

      Enabling audio logs the controller's identity and its send path, so a recurrence names itself
      rather than looking like a second pad:
      `Audio enabling on controller 0x…, sub-device 3, transport isochronous|GIP link`.
      Two lines with different pointers is two controllers for one pad; the `GIP link` one is the
      fault.

      *Streaming was unaffected throughout - both measured runs show zero frame loss - so this is
      an audio-path fault only.*
- [ ] **Audio is as good on the second session as on the first.** This is the check for the ring
      re-priming fix, and it is the one that reproduced the fault: enable pad audio, listen,
      disconnect from the menu, reconnect, and enable it again. Before the fix the second session
      and every one after it played gapped and slightly slowed, because disabling audio leaves the
      stream up carrying silence - so the ring drains flat - and re-enabling resumes into the
      running stream without ever rebuilding the cushion. Do three or four sessions in a row: the
      old fault got no worse after the second, it was simply absent from the first and present from
      then on.

      Then do the same across an **app restart**, which is how it was reported.

      Read the session summary rather than trusting your ears alone, since the counters separate
      this from the device-side fault below:

      ```
      Audio session: N packets sent, N bytes queued, N bytes dropped, N late, N send failures,
                     N underruns, last flow rate 192
      ```

      Underruns climbing while bytes dropped stays at zero is a starved ring, which is this.
      Bytes dropped climbing instead is the opposite - the host outrunning the link - and is not.
      A flow rate that sits at 196 rather than moving between 188, 192 and 196 means the device is
      asking for more than a 48 kHz source can supply, which no amount of cushion fixes.

      Expect a few milliseconds of silence at the start of each session, and after any gap: that is
      the cushion being rebuilt, and it is deliberate.
- [x] **The ring collapses after a relaunch and not on a fresh session.** *Measured on the Shield,
      2026-08-19, cabled Xbox One pad.* A fresh session primed once and never again in 60 s, and
      summarised as 56208 packets sent, 128 bytes dropped, 2 late, 0 send failures, 1 underrun,
      flow rate 192 - healthy. The session after a kill-and-relaunch re-primed six times in four
      minutes, 20-80 s apart, on identical host supply. So the ring genuinely runs dry after a
      relaunch, which the old build could not show: priming was a one-shot latch, so a collapse was
      silent and permanent instead of logged and recovered.

      Each `Wired: audio buffer primed, streaming` after the first is one collapse. Counting them is
      now the cheapest read on this fault.
- [x] **It is not the flow rate, and it is not the pad.** *Measured 2026-08-19.* The pad asked for
      192 bytes per millisecond in every session and never once asked for more, dropped nothing and
      failed no sends, and everything queued was drained. What differed was supply: 94% of 48 kHz
      stereo on a healthy session against 23% on the bad one.

      **The host sends no audio at all when the PC is silent** - `Received first audio packet after
      11500 ms` with the PC idle, against `100 ms` with music playing. So a dry ring is normal, and
      the old build's one-shot prime turned the first dry spell into permanent degradation.

      Divide `bytes queued` by the time audio was enabled and compare against 192000 B/s before
      blaming the pad for anything.
- [x] **The announce is the discriminator, and a sub-device RESET cannot restore it.** *Measured
      2026-08-19.* A clean session opens with `Device 3 announced`; a stuttering one goes straight
      to metadata. Five sessions, exact correlation, with 100% supply and no underruns in both -
      so it is device state, not our pipeline. Watch for `announced yes|no` at discovery.

      Resetting the stale sub-device and waiting for the hello §3.1.1 promises was tried on
      hardware: **the pad never announces again**, so the session ends with no audio device at all
      and the menu reports "no headset detected". Reverted. Do not re-try this without a way to
      recover a sub-device that goes silent.
- [!] **Adopting the stale configuration does not cure the stutter.** *Measured 2026-08-19.* The
      mechanism works: `announced no` -> `adopting its configuration` -> volume six milliseconds
      later -> streaming, with no format proposal and no fallback. The two sessions then measure
      identically - 100.0% supplied, ~1000 packets/s, zero dropped, late, failed and underrun, flow
      rate 192 in both - and the adopted one still stutters.

      **Stop looking for a protocol fix.** Every GIP state message has now been tried; the table in
      `AUDIO.md` has all seven. Unplugging the cable is the answer. The change was kept because it
      is what 2.2.11 and xone both describe and it sends strictly less to the device, not because
      it helped - do not read it as a fix.
- [ ] **A relaunched session adopts the pad's configuration instead of rebuilding it.** The check
      for the stutter itself. Unplug first for a clean baseline, then:

      1. Fresh session - expect `announced yes` and the full sequence. Must be no worse than today.
      2. Kill Moonlight, relaunch, reconnect, enable audio - expect `announced no`, **no**
         `proposed format` line, and `adopting its configuration` followed by
         `device reported volume, streaming`.
      3. The summary should still read ~192000 B/s supplied and ~1000 packets/s. It read that while
         stuttering too, so this confirms no regression rather than success - the ears decide.

      If `no volume after 3000 ms; renegotiating the format` appears, the pad did not accept a bare
      START: audio starts three seconds late and behaves as it did before. That is the fallback
      working, not a new fault.
- [ ] **START is repeated until the volume arrives.** 2.2.11 asks for it at 500 ms intervals for up
      to 3 s and it was never implemented, so a lost START left audio enabled and silent. Hard to
      provoke deliberately; the fallback line above is the evidence it runs.
- [ ] **The TV stream is closed while a pad has the audio, not merely silent.**
      ```bash
      adb shell dumpsys media.audio_flinger | awk '/name AudioOut_D,/,/Hal stream/'
      ```
      Expect `Audio moved to a pad; local output closed`, and the stream gone rather than idling.
      Then toggle pad audio off and on **several times mid-stream**: TV audio must come back within
      a buffer or two each time (`Audio returned from the pads; local output reopened`). The rebuild
      is the risk here, not the close - `Unable to reopen local audio output` means it failed, and
      the session stays silent on the TV by design rather than half-open.

      The mediashell `ENCODING_E_AC3_JOC` crash in logcat is a Google bug on audio route changes,
      already present at stream end, and closing the stream will trigger it more often. Not ours.
- [!] **A USB reset does not recover a stale pad, and takes the controller with it.** *Measured
      2026-08-19, and withdrawn.* Retried on a proper footing - detach handling in place and
      verified, permission persisting, the pad re-claimed directly instead of waiting on a broadcast
      that never comes - and it still fails in two distinct ways.

      `USBDEVFS_RESET` does not re-enumerate a device whose descriptors are unchanged: it resets the
      port and restores it in place. So no attach broadcast follows and nothing re-claims the pad on
      its own. Re-claiming it by hand then finds a device that opens, accepts the claim, and answers
      nothing - no metadata, no hello, no input - for as long as it is watched (63 s here), until
      the cable is physically pulled.

      The menu action for it is reverted. It bricked the controller until a replug, which is a worse
      fault than the stutter it was meant to fix. **Do not try a fourth time**; see `AUDIO.md`.
- [!] **Holding the isochronous stream until the volume message did not cure the relaunch
      stutter.** *Measured 2026-08-19.* The gating works as designed - `awaiting the device` at
      enable, `Wired: streaming` only after the volume - and the stale session stutters exactly as
      before. Kept anyway: it matches both references and sends strictly less to a device in a
      state the spec never defines, which is the same standard the adopt change was kept on.
- [x] **The pad's rate adaptation moves - and on a stale pad it thrashes.** *Measured 2026-08-19,
      the first time this fault has been visible from our side:*

      | | Fresh (clean) | Stale (stutters) |
      |---|---|---|
      | Flow range | 188-196 | 188-196 |
      | Changes | 261 / 76 s = **3.4/s** | 693 / 58 s = **12/s** |

      Fresh is textbook drift-dither: one +/-4-byte nudge every ~300 ms, which is what ~50 ppm of
      clock offset needs. Stale is a sustained oscillation at 3.5x that rate for the whole session,
      through delivery our counters score as perfect. The pad's buffer controller is in a limit
      cycle, and the stutter is its fill bouncing off a rail.
- [!] **Halving the isochronous queue did not calm the loop, which settles the mechanism.**
      *Measured 2026-08-19.* At 12 ms of queue the stale session ran 1067 flow changes in 88 s -
      12.1/s, identical to 12.0/s at 24 ms - while the fresh control stayed at 3.85/s. The
      oscillation is completely insensitive to host-side actuation delay: it is internal to the
      pad, its period is the pad's own, and no queue size will fix it. The queue stays at three
      transfers for the 12 ms of pad-audio latency it saves, with delivery measured clean at that
      depth (0-1 underruns per session); it is not a stutter fix and must not be read as one.

      The perceived pitch drop in stale sessions fits the same mechanism: the pad's own buffer
      inserting silence as its controller oscillates is what "audio interleaved with silence
      sounds slowed down" describes, this time on the far side of the cable.
- [x] **The stale-pad rows in the pad audio menu.** *Verified on the Shield, 2026-08-19.* With a
      pad left streaming by a killed process the row carries the replug hint, and reconnecting the
      cable clears it back to plain On/Off. This is the close-out of the whole investigation: the
      fault is the pad's own, unreachable from the host, and the menu turning a mystery stutter
      into an instruction is the fix that ships.
- [ ] **The re-prime recovers a real silence gap.** The check the fix actually needs, and the one
      run 2 did not exercise - it had continuous audio, so the ring never collapsed. Play audio,
      let the PC go **fully silent for a minute**, then play audio again. It must come back clean.
      Each recovery logs one `Wired: audio buffer primed, streaming`.
- [ ] **Whether the flow rate explains it.** The collapse rate implies a supply deficit of roughly
      0.1 bytes per millisecond - about 0.05%, the size of a clock drift rather than a fault. What
      the pad is actually asking for is in the session summary and on the overlay; a rate parked at
      196 rather than moving between 188, 192 and 196 would mean it wants more than a 48 kHz source
      can supply, and no cushion fixes that.
- [ ] **The re-prime does not thrash on a healthy stream.** Untested trade-off, and the risk the
      fix carries. A completely empty ring now costs a full prefill of deliberate silence, where
      before it cost one padded millisecond, so a stream that empties the ring *often* would chop
      rather than merely degrade. It should not happen - the verified sessions report no shortfalls
      at all - but listen across a long session and check underruns are not climbing steadily.
- [ ] **Rate adaptation's effect is unmeasured.** The pad asks for 188, 192 or 196 bytes per
      millisecond in the flow field of its Audio Capture messages, and now gets what it asks for -
      3.2.5.1.5 calls honouring it "the mechanism GIP devices use to eliminate pops and clicks".
      Whether it prevents audible drift over a long session has not been tested.
- [ ] **Two pads at once on a cable**, or one cabled and one wireless together. Never tried.
- [ ] **A pad on a USB cable without the driver enabled is not offered pad audio at all**, rather
      than being offered it and going silent. Confirm the menu entry stays absent when
      *Headphone audio on cabled Xbox pads* is off.

---

## 11. Steam controllers announced as `LI_CTYPE_STEAM`

Valve's vendor ID falls through `sendControllerArrival()`'s vendor switch to
`guessControllerType()`, which now answers `LI_CTYPE_STEAM` for the three Valve gamepad types
SDL's database lists. The type only selects which button glyphs the host draws, so a wrong
answer is cosmetic — but it is cosmetic *on the host*, where nothing in this client's logs
will show it.

- [ ] **A Steam Controller pairs and is playable at all.** More basic than the glyphs, and
      not a given: this client has never been tested against one.
- [ ] **The host draws Steam prompts, not Xbox or generic ones.** Sunshine and GFE both pass
      the type through to the game, so check in a title that shows controller glyphs.
- [ ] **A HORIPAD for Steam is still reported as unknown.** It is deliberately left unmapped —
      Steam-branded, but with no touchpads, gyro or grips — so it should keep whatever the
      host guesses for an unknown pad rather than gaining Steam prompts.

---

## 12. Input path: two-controller checks

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

## 13. USB controller motion is gated on what the host asked for

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

## 14. Overlay composition

**Closed, negative, and reverted.** The overlays were briefly made opaque black (`#FF000000`) on
the theory that their translucent background forced the hardware composer to blend, costing the
video its own plane and making the performance overlay change the frame timing it exists to
measure. Measured on the Shield TV, the theory is wrong twice over. Both overlays are back to
`#80000000`. **Do not retry this** — the reasons it failed are not device-specific bad luck.

### What was measured

Shield TV at 4K, streaming at 4K, release build with the opaque change installed and confirmed
present in the APK (`aapt2 dump xmltree` showed `android:background=#ff000000` on both TextViews).
The comparison apps each set their own refresh rate, so the output mode is given per state below.

```bash
adb shell dumpsys SurfaceFlinger    # the vendor "h/w composer state:" block is the part that matters
```

| State on screen | Output mode | Nvidia HWC 2.0 composition |
|---|---|---|
| Launcher, no video | 4K @ 59.94 | `1 layers in a scratch buffer` |
| Our stream, overlay **off** | 4K @ 60 | `1 layers in a scratch buffer` |
| Our stream, overlay **on** | 4K @ 60 | `2 layers in a scratch buffer` |
| YouTube TV, 4K video | 4K @ 59.94 | `2 layers in a scratch buffer` |
| Kodi, 4K video | 4K @ 23.976 | `3 layers in a scratch buffer` |

In all five, `Compositor: draw_arrays` and every physical display window is idle except the one
holding the scratch buffer:

```
Window 0 (phys 0 caps 1f5): unused
Window 1 (phys 2 caps 1d5): unused
Window 2 (phys 3 caps 0):   unused
Window 3 (phys 1 caps 469 blend 0x100 xform 0x0 z=3): scratch containing N layers
```

### Why it cannot work

**1. A child View's background alpha never reaches the compositor.** Layer opacity comes from the
*window's* pixel format, not from a `TextView`'s background. `Game`'s window has to stay
translucent so the `SurfaceView` can show through it — that is what the layer's
`transparentRegionHint` is for — so the layer stays `blend=PREMULTIPLIED`, `isOpaque=false`,
RGBA_8888 no matter what colour goes in the layout. `#80000000` and `#FF000000` are identical to
the composer. The only lever that would change it, `getWindow().setFormat(PixelFormat.OPAQUE)`,
would black out the stream.

**2. There is no hardware plane to lose.** The Tegra composer puts everything through a scratch
buffer regardless — including Google's own 4K video app, and including the launcher with no video
at all. It is unconditional policy at this display configuration, not something our surface
provokes.

That second point also disposes of the obvious follow-up, that our decoder hands back a buffer the
display windows cannot scan out. Three different Tegra video formats were observed across the
three apps — ours `0x16b`, YouTube's `0x146`, Kodi's `0x18b` — and all three land in the same
scratch buffer. Kodi is the strongest of the comparisons: it drove the display into a 23.976 Hz
mode to match its content and put *three* layers up (video, its 1080p UI window, and a second
720p SurfaceView upscaled to 4K), and still got no plane.

### What this corrects elsewhere

`CLAUDE.md` claimed the overlay "forces GPU composition" on this hardware. It does not — the
composition pass happens with the overlay down, with the app backgrounded, and in other apps. That
sentence has been corrected. The overlay does still perturb what it measures, by adding a second
layer to the pass and by costing the decode thread its formatting work, so *compare overlay-on
with overlay-on* still stands; the reason given for it was wrong.

Our stream is also the leanest of the three on layer count: one layer with the overlay down, where
YouTube pays two permanently and Kodi three.

### Not tested, and deliberately not chased

- [ ] **Homatics Box R 4K.** Its Amlogic composer could behave differently. Left unrun because the
      mechanism in point 1 above is platform-independent — the change could not work there either,
      whatever its composer does with planes.
- [ ] **Whether shallower HDMI signalling frees the display windows.** The link runs
      `Rec.2020, 12-bit YUV422` with `[Dynamically switching Rec.709 on]` in every state above, and
      a shallower mode might let the composer scan out directly. Refresh rate is already ruled out
      — three of them appear in the table (60, 59.94 and 23.976 Hz) and all composite the same way
      — so only the colour depth and colorspace are untested. Not worth chasing: trading output
      quality for one composition pass is a bad deal, and no third-party app gets a plane either.

---

## 15. Copy-free picture data submission

`f8a8ae8a` (#9) stopped routing picture data through a Java `byte[]`. Native now takes the address of
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

### 15.1 Smoke, per device × codec

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
> run reads as a full pass. This is the case 15.2 exists for, and on a clean link it is the *normal*
> outcome, not an unlucky one.

### 15.2 Forced IDRs — the offset case

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
software — fewer at a time, so they suit 15.1 rather than the ≥30 this section wants:

- Alt-Tab away from the game and back
- Toggle the game between fullscreen and windowed
- Change the host's resolution or refresh rate
- Win+Alt+B (HDR toggle) — this one also forces a codec restart, see 15.3

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
> `Fused CSD frames: 0`. The toggle is for 15.3; it is actively counterproductive here.
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

### 15.3 Codec recovery — the memory-safety window

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

### 15.4 Abort and buffer reuse

`abortPicData()` clears the retained buffer; `fetchNextInputBuffer()` short-circuits while it is
non-null. If the clear were missed, a retried fused IDR would prepend CSD twice and shift every
subsequent write by a constant.

- [x] `Picture data aborts` is **non-zero** — otherwise this path was never exercised and the box
      below is not a pass. Aborts occur during recovery quiesce, so 15.3 is the way to provoke them.
      *Shield 2026-08-10: 1 abort, during the codec restart from the HDR toggle.*
- [x] `Picture data invariant failures: 0` across a run containing aborts.
      *0. The fused IDRs that followed the abort did not double-prepend CSD — that failure would
      have shifted every subsequent write by the CSD length and lit this counter up.*

### 15.5 CheckJNI

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

### 15.6 Performance — what is and is not measurable

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
| 82 Mbps | 10.26 ns/byte | 9.65 ns/byte | **-15.0%** |
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

### 15.7 Known gaps

- **ASan would not catch an overrun of the destination.** MediaCodec input buffers are allocated by
  the codec service and mapped in; they carry no ASan redzone, so a write past the end lands in
  adjacent mapped pages silently. ASan does still cover over-reads of the source `LENTRY` buffers,
  which are `malloc`'d by the depacketizer. Worth knowing before spending a day on it — 15.3's NPE
  detector and 15.5 cover the actual hazard better.
- **HWASan is unavailable on both devices.** It needs arm64 plus Android 14 or a HWASan system
  image: the Shield is arm64 but API 30 on a stock image, the Homatics is Android 14 but 32-bit.

---

## 16. Loss recovery (carried patches 0003–0005)

All three need deliberate packet loss to mean anything. Stream over Wi-Fi at a distance, or
shape the link — the point is to force the FEC queue to give up on whole frames, not merely
to drop the odd packet.

### IDR request on FEC-detected loss (patch 0003)

This only reaches the patched branch on a client streaming **without** reference frame
invalidation, which means an **Amlogic box** — the Shield TV keeps RFI and never takes it.
Confirm which side you are on first: absence of `will use reference frame invalidation for
HEVC` in logcat for the chosen decoder is the check.

- [ ] **`Reached consecutive drop limit` stops appearing** on ordinary loss. That line was
      the old recovery mechanism firing, 120 frames after the loss; seeing it now means the
      patch is not doing its job. This is the single most diagnostic line for the fix.
- [ ] **Recovery is roughly a frame interval plus a round trip**, not the 2 s at 60 FPS / 4 s
      at 30 FPS it was. Time it against a moving scene rather than a menu.
- [ ] **`Waiting for IDR frame` is followed promptly by a recovered picture**, rather than
      repeating while the video stays frozen.
- [ ] **No regression on the Shield TV.** RFI is on there, so the RFI request path is what
      should still run — `Sending RFI request for unrecoverable frame` — and behaviour should
      be identical to before the patch.
- [ ] **Fire TV Cube keeps its fast path.** `Enabling HEVC RFI on confirmed-safe Amlogic
      device` must still appear, since that device is the exception to the Amlogic rule and
      should therefore also *not* take the new branch.

### Atomics (patch 0004)

No behaviour change is expected; this is a regression check on the teardown path, which the
patch touches on every connection.

- [ ] **Connect and disconnect ten times in a row** without a hang or crash.
- [ ] **Background the app mid-stream** (Home, or a PiP transition) and return. `onStop()` is
      one of three entry points into the interrupt path and fires on ordinary backgrounding.
- [ ] **Disconnect during the handshake**, before the stream starts — this is the case where
      `interruptConnection()` races a `startConnection()` that holds the monitor.
- [ ] Verify on **armeabi-v7a**, not only arm64. 32-bit codegen is where this would show.

### Intra refresh (patch 0005)

Needs **Sunshine on an NVIDIA GPU**; AMD and Intel hosts parse the attribute and ignore it.

- [ ] **Default off is unchanged.** With the setting off, the stream behaves exactly as
      before. This is the one that matters — the feature is opt-in precisely so the default
      path stays untouched.
- [ ] **Toggling it on starts a stream at all**, and the host log shows intra refresh enabled.
- [ ] **Watch static and low-complexity scenes** — menus, pause screens, a stationary camera —
      for shimmer or creeping corruption. This is the known failure mode reported by the Xbox
      client, and the reason the setting is experimental.
- [ ] **Recovery after loss is smoother**, without the bitrate spike and visible hitch a
      keyframe produces. If it is not, the feature is not earning the risk.
- [ ] **An AMD or Intel Sunshine host still streams normally** with the setting on.

---

## 17. Fractional refresh rates under "cap FPS" pacing

The client used to request `roundedRefreshRate - 1` whenever cap-FPS pacing met a display at or
below the requested rate — 59 fps on a 59.94 Hz panel. It also sent the panel's exact rate as
`clientRefreshRateX100`, which Sunshine can turn into a precise 30000/1001 encode. It never got
the chance: Sunshine discards that value when it differs from the requested rate by more than 1%
(`src/rtsp.cpp`), and 59.94 against 59 is 1.6% out. The host fell back to integer 59 every time.

The client now asks for 60 on such a display and lets the exact rate through the guard, so the
stream should land on 59.94 rather than a whole frame below it.

**Take the numbers from the end-of-stream summary, not the overlay.** On this hardware the overlay
forces GPU composition, so it changes the frame timing it is measuring. Compare overlay-off runs.

- [ ] **Set each box to a 59.94 Hz output mode** and confirm the client logs
      `Fractional display rate 59.94; requesting 60`. If it logs nothing, the display is reporting
      a whole 60.000 and this section cannot be tested on it — record that and move on.
- [ ] **Stream at 60 fps with frame pacing set to "cap FPS"** for several minutes of steady
      motion, and record from `globalVideoStats`: frames received, frames rendered, and the
      dropped/discarded counts.
- [ ] **Compare against the same run on the previous build.** The pass condition is that the slow
      periodic drop or duplicate — roughly one per 17 seconds at 59 fps against 59.94 Hz — is gone,
      with no new stutter in its place.
- [ ] **The Sunshine host log retains the rate.** It should not log the value being discarded, and
      the encoder should report 59.94 rather than 59.
- [ ] **A whole-number display is unchanged.** At a true 60.000 Hz the client must still request
      59 — that path is deliberately untouched, and it is the one that protects against queueing.
- [ ] **A non-Sunshine host still behaves.** This is the risk case: if the host ignores
      `clientRefreshRateX100`, the client is now asking for 60 on a 59.94 Hz panel, which is
      exactly the over-rate condition cap-FPS pacing exists to avoid.

Reading `LimeLog` output on the Homatics needs `adb shell setprop persist.log.tag '""'` first, and
`adb shell setprop persist.log.tag S` afterwards to restore the shipped value.

---

## 18. Client unique ID

The `uniqueid` query parameter rides on **every** HTTP request — pair, unpair, serverinfo,
applist, launch, resume, cancel — so this is not just a launch-path change. The default is
unchanged, which makes the first block the check that actually matters.

### 18.1 Setting off (default) — regression check

- [ ] The wire value is still `0123456789ABCDEF`. Nothing about pairing, browsing, launching,
      resuming or quitting differs from a pre-change build.

### 18.2 Setting on

- [ ] `adb shell run-as com.limelight.debug cat files/uniqueid` gives this install's ID, and the
      wire value now matches it.
- [ ] **Already-paired host:** launch, resume and quit without re-pairing. Sunshine identifies
      paired clients by certificate, so this should hold — confirming it is the point.
- [ ] **Fresh pair:** unpair, pair again, stream. Exercises `uniqueid` on the pairing endpoints.
- [ ] **Toggle takes effect without restarting the app.** The preference is read per call for this
      reason. Two paths legitimately lag: box art keeps the ID captured when `AppGridAdapter` was
      built, and a running stream keeps the one it launched with. Everything else should switch
      immediately.
- [ ] **Two Moonlight clients, if available:** start a session from the other client and try to
      quit it from this one. Expected to fail now. **Record what actually happens** — this is the
      cost of the setting, and the summary string should describe real behaviour rather than a
      prediction.
- [ ] **Turn it back off** and confirm shared-ID behaviour returns against the same host with no
      re-pairing. A setting that cannot be reversed safely is worse than no setting.

---

## 19. YUV 4:4:4 decoder profile survey

Not a feature test — the experiment that decides whether 4:4:4 negotiation is worth writing at
all. Debug builds now log every decoder's raw profile and level integers at decoder construction.

Raw integers because Android exposes no constant for HEVC RExt 4:4:4 or AV1 High 4:4:4, so there
is nothing to match `profileLevels` against from the SDK.

- [ ] **Shield TV:** capture the `Decoder capabilities:` logcat block and record the
      `video/hevc` and `video/av01` profile integers below.
- [ ] **Homatics Box R 4K:** same.
- [ ] Compare against the Codec2/OMX values for HEVC RExt 4:4:4 and AV1 High 4:4:4.

| Device | `video/hevc` profiles | `video/av01` profiles | Any 4:4:4? |
|---|---|---|---|
| Shield TV | *(fill in)* | | |
| Homatics Box R 4K | *(fill in)* | | |

If neither reports a 4:4:4 profile — the expected result — record that and leave 4:4:4 deferred.
H.264 High 4:4:4 alone is 8-bit and effectively unsupported by Android hardware decoders, so it
does not change the answer on its own.

---

## 20. ARMv8 crypto extensions on the 32-bit build

The config has always asked for `MBEDTLS_AESCE_C`, but `aesce.c` gates its whole body on
`MBEDTLS_ARCH_IS_ARMV8_A`, which `build_info.h` derives from `__ARM_ARCH >= 8`. The NDK compiles
`armeabi-v7a` as `armv7-a`, so on that ABI the file collapsed to a 684-byte stub containing no
crypto instructions, and both AES and GHASH ran through table lookups. The Homatics has been
paying that cost since the fork began; the Shield never did, because `arm64-v8a` satisfies the
gate on its own.

`moonlight-core/Android.mk` now raises `-march=armv8-a` for the mbedtls module on that ABI only.
Verified in the shipped release library: `mbedtls_aesce_crypt_ecb` contains 54 `aese.8`
encodings where it previously contained none. `llvm-objdump` renders them as `<unknown>` when
disassembling the linked `.so`, because link-time attribute merging leaves it marked `ARM v7`;
disassemble `aesce.o` instead, where they decode properly. The bytes execute either way — the
tag is metadata.

Measured with `psa-freeze-investigation/cryptobench.c`, a 32-bit binary on Cortex-A57, ns per
packet on the legacy path:

| Path | `armv7-a` | `-march=armv8-a` | |
|---|---:|---:|---|
| video, 1392 B | 36,933 | 6,683 | 5.5× |
| video, 1024 B | 27,396 | 4,934 | 5.6× |
| audio, 240 B | 3,247 | 729 | 4.5× |

Those are per packet on the receive threads, so at ~3,600 video packets/s the 32-bit build was
spending something like a fifth of a core on decryption alone. The numbers above are a proxy:
they were taken on the Shield running a 32-bit binary, not on the Homatics, whose Cortex-A55 is
a different core. The ratio should hold; the absolute figures will not.

Dispatch is runtime-guarded — `mbedtls_aesce_has_support_impl()` checks
`getauxval(AT_HWCAP2) & HWCAP2_AES` and falls back to tables — so a CPU without the extension
still decrypts correctly. What is *not* guarded is that clang may now emit ARMv8-A baseline
instructions elsewhere in mbedtls, which would fault on a genuine ARMv7 CPU. The S905X4 is
ARMv8-A, so nothing in scope is affected, but this is the line to revisit if a 32-bit ARMv7
device is ever added.

- [ ] **Homatics: the stream still decrypts.** The whole risk is that the extensions are
      compiled in but the CPU or kernel does not advertise them, in which case AES silently
      produces garbage rather than falling back. Video failing is obvious; audio is not, so run
      the §5 check as well:
      ```bash
      adb logcat -d | grep -a "Failed to decrypt"   # must return nothing
      ```
- [ ] **Homatics: the extensions are actually being used.** If `HWCAP2_AES` is absent the build
      is correct but no faster, and the table path is still running:
      ```bash
      adb shell cat /proc/cpuinfo | grep -i features   # expect aes, pmull, sha1, sha2
      ```
- [ ] **Homatics: measure it.** Push the 32-bit `cryptobench` and confirm the ratio on the real
      SoC, rather than trusting the Cortex-A57 proxy above.
- [ ] **Shield TV: unchanged.** The `ifeq` is scoped to `armeabi-v7a`, so `arm64-v8a` should be
      byte-identical apart from unrelated changes. Confirm a stream still runs.

## 21. xow driver JNI binding

`xow_driver_jni.cpp` now exports only `JNI_OnLoad` and binds its twenty entry points with
`RegisterNatives`, where before each was an exported `Java_com_limelight_binding_input_driver_*`
symbol resolved by the runtime's name mangling. `GipCrypto::init()` takes the class instead of
calling `FindClass` on a hardcoded name. Verified in the built libraries for both ABIs: one
exported `JNI_OnLoad`, no `Java_*` symbols, and exactly four `com/limelight` strings — the block
of class names at the top of the file.

Nothing on a per-frame or per-report path changed. Once bound, a registered native and a mangled
one are the same function pointer; only the one-time resolution differs, and it moves from first
call to `System.loadLibrary()`.

What cannot be checked off a device is the class-initialisation order at load time. `FindClass`
initialises the class it returns, so binding `GipController` runs its static initialiser, which
calls `System.loadLibrary("xow-driver")` again on the thread already inside `JNI_OnLoad`. ART is
documented to recognise that by thread id and return success rather than deadlock, logging
`recursive attempt to load library`. Three classes here each load the library, so whichever the
app touches first takes that path for the other two.

- [ ] **Either device: the library loads at all.** Plug in the wireless adapter or a wired pad and
      confirm no `UnsatisfiedLinkError` and no hang. The recursion above, if ART did not handle it,
      would present as a hang inside the static initialiser, not a crash.
      ```bash
      adb shell setprop persist.log.tag '""'
      adb logcat -d | grep -aiE "xow-driver|recursive attempt|UnsatisfiedLink"
      adb shell setprop persist.log.tag S
      ```
      The `recursive attempt to load library` line is expected and is not a fault.
- [ ] **Either device: every entry point still binds.** A signature that drifted now fails at load
      rather than at first call, so a clean load proves all twenty. `Crypto: GipCrypto ready`
      confirms the crypto class resolved too — it is logged from `JNI_OnLoad` now, not from the
      first controller connect.
- [ ] **Wireless adapter: a pad still pairs and reports.** The security handshake is the only
      caller of `GipCrypto`, so pairing is what proves the class reference survived the move off
      `registerNative()`. Rumble and headphone audio exercise the rest of the table.
- [ ] **Wired pad: the static entry points bind.** `XboxWiredGipController`'s four natives are
      `static`, which `RegisterNatives` handles identically but which the mangled-name path
      reached through a different lookup. Confirm a cabled pad still enumerates.
- [ ] **Release build, not just debug.** `assembleRelease` and `lintRelease` pass and the release
      library exports the same single `JNI_OnLoad`, but that only proves it builds. R8 keeps the
      whole `driver` package, and the tables are matched by method name at runtime, so a release
      APK still has to be run on hardware to prove the binding holds under shrinking.

---

## Hardware still needed

| Needed for | Hardware |
|---|---|
| §2 in full | Nintendo Switch Pro Controller + USB cable |
| ~~§3 latency claim~~ | ~~A device with the AudioTrack fast-path bug~~ — met: the Homatics is one, measured at 169.6 ms vs 22.6 ms |
| §3 surround | 5.1 or 7.1 output on the host |
| §4 pads | Xbox Series S/X pad, 8BitDo pad, PowerA Pro |
| §4 decoder | Amlogic-based Android TV device |
| §1 Win+Shift+Left | Dual-display host |
| §6 rumble | Wired Xbox pad **and** a wireless pad + Xbox Wireless Adapter, to compare the two |
| §6 duplicate pads | Xbox Wireless Adapter, ideally with four pads |
| §6 refresh rate | Display running a fractional mode (59.94/29.97/23.976 Hz) |
| §7 battery, extended status | Xbox Series X\|S pad, plus a play-and-charge kit or USB cable |
| §7 JNI input path | Four pads on one adapter, for a sustained session |
| §8 metadata | Any adapter pad; ideally several generations, since what they report is the point |
| §9 v2 security | An Xbox Elite Series 2 — it asks for v2, and the exchange is verified against it |
| §9 multi-pad | Four pads on one adapter, to confirm per-pad sequence pools |
| §10 pad audio | Two adapter pads with integrated 3.5 mm jacks, and wired headphones for each |
| §11 Steam type | A Valve Steam Controller, and a HORIPAD for Steam for the negative case |
| §12 both items | A USB-driven pad *and* an Android-enumerated pad, connected together |
| §13 motion | A pad with a gyro (Switch Pro, DualSense, DualShock 4) + a host game that requests it |
| §16 intra refresh | Sunshine host on an NVIDIA GPU |
| §17 refresh rate | A display or output mode that reports a fractional rate (59.94, 29.97, 23.976) |
| §18.2 two-client check | A second Moonlight client against the same host |
| §19 | Both target devices; the survey differs per SoC |
| §20 | The Homatics specifically — the Shield cannot verify a change scoped to `armeabi-v7a` |
