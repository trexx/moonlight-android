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
- [ ] **Unplug and replug mid-stream: the controller comes back, and the app does not leak the
      USB interface or crash.** Expect this to have *failed* before the detach handling landed -
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
| *(fill in)* | | | |

---

## 9. GIP security handshake

The driver now performs the [MS-GIPUSB] security exchange when a pad connects. **This is not a
security feature** — the certificate is never validated and the link is still unencrypted. It is
here because §2.2.1.4 makes it the gate on everything else: *"GIP supports enumeration of additional
sub-devices after the primary device has completed the Security Handshake successfully."* No
handshake, no sub-devices, and therefore no headphone audio.

Only **v1** (RSA, commands `0x01`–`0x08`) is implemented. The data header's version byte selects,
and a device asking for v2 (ECDH P-256, `0x21`–`0x27`) is logged and left unauthenticated rather
than failed obscurely.

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
- [ ] **A v2 pad is detected and logged**, not silently broken. Expect `device wants protocol v2,
      which is not implemented`. **No v2 hardware has been available** — this is the item that
      needs it.
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

**This works.** An Xbox One pad (PID `02dd`) over adapter `045e:02fe` on the Shield TV plays the
stream's audio through its headphone jack, and the TV falls silent while it does. It took the
security handshake in §9 to get there — audio is a sub-device, and sub-devices do not appear until
the pad has authenticated. Details in `app/src/main/jni/xow_driver/AUDIO.md`.

The first two items are the ones that decide whether this ships at all, and **the second is still
open** — the feature works but has not been shown to be worth using.

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

## Hardware still needed

| Needed for | Hardware |
|---|---|
| §2 in full | Nintendo Switch Pro Controller + USB cable |
| §3 latency claim | Google TV Streamer, or another device with the AudioTrack fast-path bug |
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
| §9 v2 security | A pad that uses the ECDH handshake — none has been available to test against |
| §9 multi-pad | Four pads on one adapter, to confirm per-pad sequence pools |
| §10 pad audio | Two adapter pads with integrated 3.5 mm jacks, and wired headphones for each |
