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
| A | Homatics Box R 4K Plus (Amlogic S905X4) | 14 / API 34 | *(fill in)* | `armeabi-v7a`; the box §3's original audio numbers came from |
| B | NVIDIA Shield TV (`mdarcy`) | 11 / API 30 | Turk-PC, 4K60 HDR, HEVC | `arm64-v8a`; lowest supported API, so API-gated features stop here |
| C | *(fill in)* | | | phone/tablet, for touch + on-screen keyboard |

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

> **Un-silence logging before running any of this.** The Homatics ships `persist.log.tag=S`,
> which silences the whole main buffer, so every log check in this section passes silently
> whatever the truth is. Same caveat as §5, and it applies here too:
> ```bash
> adb shell getprop persist.log.tag          # note the original value
> adb shell setprop persist.log.tag '""'
> adb logcat -c
> ```
> Restore it afterwards. Note also that the AAudio startup line is easy to lose even with
> logging on: the buffer is only 256 KiB and HDMI CEC chatter floods it within minutes, so
> read it soon after the stream starts. On a long session, capture continuously
> (`adb logcat -v time MoonlightAAudio:V '*:S' > audio.log &`) rather than relying on `-d`
> afterwards, or the session-end summary can rotate out before you read it.
>
> The Shield TV does **not** ship `persist.log.tag=S` — `getprop` returns empty there and the
> main buffer works as shipped, so this step is Homatics-only.

- [X] **Setting off: renderer selection is unchanged.** AudioTrack is still chosen, and no
      AAudio stream is opened (`adb logcat -d -s MoonlightAAudio:V` is empty). Note this no
      longer means "no new logging" in general — the AudioTrack path reports its own granted
      configuration now, by design, since that is the path most users are on.
- [ ] **Both paths report what was *granted*, not what was requested.** With the setting on,
      the AAudio startup line must name a performance mode; with it off, the AudioTrack line
      must do the same:
      ```bash
      adb logcat -d -s MoonlightAAudio:V              # "... low latency / <sharing mode> ..."
      adb logcat -d | grep -a "Audio track configuration"
      ```
      A downgrade warns rather than failing, so the absence of a warning is the pass
      condition. This is the check that previously required `dumpsys media.metrics`.

      Only the *performance* mode is required to be `low latency`. The sharing mode is
      device-dependent and not a downgrade in itself — the Homatics grants `exclusive`, the
      Shield refuses it and grants `shared`, and both are fine. `AAUDIO_SHARING_MODE_SHARED` is
      the knowingly accepted fallback in `openStream()`, which is why it does not warn.

      **The second command needs a debug build.** `proguard-rules.pro` strips every
      `LimeLog.info` call from release, so the AudioTrack configuration line exists only in
      debug. The AAudio renderer logs natively and so keeps its line in both. In a release
      build the AudioTrack path's answers are the downgrade *warning* — which is kept — and
      the overlay row; verify it there instead.
- [ ] **Cross-check the app against the platform.** While streaming:
      ```bash
      adb shell dumpsys media.metrics \
        | grep -aE "performanceModeActual|sharingModeActual|burstFrames|bufferSizeFrames"
      ```
      `performanceModeActual` and `sharingModeActual` must agree with what the app logged.
      **Disagreement here invalidates everything downstream** — it means the readback is
      wrong, and the overlay and session summary are reporting fiction. Baseline measured on
      the Homatics Box R 4K Plus: `LOW_LATENCY`, `EXCLUSIVE`, `burstFrames=384`,
      `bufferSizeFrames=768`.

      **This check does not exist on the Shield TV.** API 30's `media.metrics` keeps no
      `aaudio` records at all — only `audio.track`, `audio.thread`, `audio.device` and
      friends — so there is no `performanceModeActual` to compare against and the grep
      returns nothing. That is not a failure, and it is not the app's readback being wrong.
      Use the platform's own log instead, which corroborates both fields independently:
      ```bash
      adb logcat -d -s AudioStreamBuilder:V AudioTrack:I | grep -aE "sharing|perfMode|FLAG_FAST"
      ```
      On the Shield that reads `perfMode = 12` (AAudio's `LOW_LATENCY`), `build() EXCLUSIVE
      sharing mode not supported. Use SHARED.` and `AUDIO_OUTPUT_FLAG_FAST successful` —
      agreeing with the app's `low latency / shared` on both counts, and confirming the fast
      mixer path was actually granted.
- [ ] **Session-end summary appears on every exit**, not just after a crash:
      ```bash
      adb logcat -d -s MoonlightAAudio:V | grep -a "session ended"
      ```
      INFO when clean, WARN when anything was wrong — including a session that played
      perfectly but never got the mode it asked for. The AudioTrack path emits its own
      equivalent through `LimeLog`.
- [ ] **Overlay shows the audio row** (enable the performance overlay in settings). It must
      name the backend actually in use, and follow a mid-session fallback from AAudio to
      AudioTrack rather than going blank. Counters that a backend does not report render as
      `—`, never as `0`.
- [ ] **Deliberate downgrade is detected.** Temporarily force it and confirm the warning,
      the log line and the overlay row all say so, then revert:
      - AAudio: set `AAUDIO_PERFORMANCE_MODE_NONE` at the `setPerformanceMode` call in
        `aaudio_renderer.c`.
      - AudioTrack: make the native-sample-rate check in `AndroidAudioRenderer.setup()`
        always `continue`, so only the standard-mode combinations are reachable.

      This is the same style of forced-failure proof §5 uses for the decrypt counter — the
      counters are worthless until they have been seen to move.
- [x] **Debug build only: counted and derived underruns agree.** Install the debug variant
      (it sits alongside via the `.debug` suffix). The `AAudio underrun detail` line reports
      both figures; they must match. Verified on the Shield TV across two sessions:
      `1982720 samples counted (1982720 derived)` and `67776 samples counted (67776 derived)`.

      **This check is weaker than it looks, and does not license trusting the derived
      figure.** The two are very nearly the same quantity: `readIndex` advances by `copied`
      and `getFramesWritten()` advances by `requested`, so
      `derived = Σrequested − Σcopied = Σ(requested − copied) = counted` algebraically. An
      exact match is therefore close to a tautology. What it does prove is narrow but real:
      that the framework's frame counter has not diverged from our own arithmetic, which
      catches a missed `readIndexAtStreamStart` baseline or a stream whose frames went
      unaccounted. It says nothing about whether the silence figure means what we think.
      Forcing starvation (drop one buffer in fifty in `nativeEnqueue`) still confirms the
      counters move, which is worth doing — it just is not independent corroboration.
- [ ] **Distinguish a startup transient from ongoing starvation.** The session summary gives
      totals only, so 0.7 s of silence at stream start and 20 s spread through the session
      look the same in a bug report. Neither the callback count nor the sample count separates
      them: they distinguish *partial* starvation from *total* (samples ÷ callbacks approaching
      the full buffer means the ring was empty, not merely short), but not *contiguous* from
      *scattered*. Until something records that, the only way to tell is to watch the counters
      move — enable the overlay and read the audio row over a few minutes. On the Shield the
      underrun counter froze at `147` and did not move across 70 s, which is what established
      that the silence was a startup transient and inaudible.

      **Do not sample it with `uiautomator dump`.** Reading the overlay text out of the view
      hierarchy looks like the obvious way to get machine-readable samples, and it wrecks input:
      the dump walks the hierarchy through the accessibility path, which synchronises against the
      app's UI thread, and `Game` dispatches controller input on that same thread. At 1 Hz it
      produced clearly late and dropped button presses on the Shield during active play — enough
      to be mistaken for a regression in whatever is being tested. It is invisible on idle
      content, which is what makes it a trap. The overlay *itself* is fine for input; it was the
      polling. Read the row off the screen, or take the session-end summary instead.

      The honest fix is a debug-only line logging the counters once a second from a cold path,
      which would make this observable without a UI round-trip at all. Nothing does that yet.
- [x] Setting on, **stereo**: audio plays, and stays in sync across a long session — 30+
      minutes — with no dropouts, crackle or drift. *(Verified on the Homatics Box R 4K.)*
- [ ] Setting on, **5.1 or 7.1**: **every speaker produces sound.** Use Windows'
      per-speaker test (Sound → Speakers → Configure → Test). This is the exact check that
      exposed the silent-surround-channels bug in ClassicOldSong #567.
- [ ] Setting on, **route change mid-stream**: unplug/replug HDMI, or switch audio output.
      Audio must recover, or fall back to AudioTrack for the rest of the session — it must
      not go permanently silent, and the stream must not hang at "Waiting for audio stream
      establishment".
- [x] **The recovered stream keeps the low-latency buffer size.** *(Failed on the Shield TV,
      fixed in `619a6b16`, re-verified there. Still unchecked on the Homatics — it may never
      have been affected, since the bug only bites if the stream actually disconnects.)*

      `nativeSetup()` sized the buffer down to two bursts, but `recoverThread()` reopens the
      stream and the replacement inherited AAudio's default — 2048 frames against a 256-frame
      burst, so **42.7 ms of queued audio where the target is 10.7 ms**. A 4x latency
      regression on the one path the whole feature exists to make fast.

      This was not an edge case on the Shield. Moonlight's own display mode switch at stream
      start (59.94 → 60.000004 Hz, plus the HDR toggle) makes the box renegotiate HDMI, which
      disconnects the audio stream **twice within two seconds of every session**, so every
      session ran on the default buffer. Symptom to check for:
      ```bash
      adb logcat -d -s MoonlightAAudio:V | grep -aE "stream started|Recovered|session ended"
      ```
      The startup line said `512 frame buffer` while the overlay read `2048 frame buffer` a
      few seconds later. That disagreement was the only visible evidence — before the fix
      neither the recovery line nor the session summary named a buffer size at all, so in a
      release build the regression was unobservable. Both now report it.

      **The number to watch is `xruns`, not underruns.** A smaller buffer trades scheduling
      headroom for latency, and underruns count the *ring* going empty (a producer problem,
      unaffected by buffer size). Sessions 1 and 2 ran at 2048 frames because of the bug, so
      session 3 is the first time 512 has held for a whole session on this box:

      | Session | Buffer | Duration | xruns | Silence | Dropped | Recoveries |
      |---|---|---|---|---|---|---|
      | 1 | 2048 | 12.1 min | 0 | 1982720 samples (20.7 s) | 3 | 2 |
      | 2 | 2048 | 11.4 min | 1 | 67776 samples (0.71 s) | 36 | 2 |
      | 3 | **512** | 5.1 min | **0** | 59072 samples (0.62 s) | 110 | 1 |

      **512 frames costs nothing measurable here.** Session 3 held it with zero xruns through
      active gameplay, and its underrun count was identical (`118`) when read mid-session and at
      teardown — frozen across roughly 3.5 minutes of real play, so there is no ongoing
      starvation at the smaller size. It was also, unintentionally, a harsher test than planned:
      the `uiautomator` polling warned about above was stalling the UI thread throughout.

      Two loose ends, neither blocking:

      - **Dropped buffers rose** (110, against 36 and 3) while recoveries *fell* to one, so it
        does not scale with recovery windows and is not explained by buffer size either — the
        ring is drained by the callback at the same rate regardless. Only 3 of the 110 arrived in
        the last 3.5 minutes, so it is a startup effect, most likely the host's jitter buffer
        flushing into a 2048-frame ring faster than it drains. 110 buffers is about 0.55 s of
        audio discarded before playback settles. Worth two or three more sessions to see whether
        110 is typical or an outlier.
      - **Session 1's 20.7 s is unexplained** and did not reproduce in either later session;
        same build, same box, same host, and inaudible. Since totals cannot say *when* it
        happened, it stays on the list rather than being written off.

      All three sessions were inaudible to the listener — no dropouts, crackle or dead patches —
      which is the check that matters and the one no counter can make.
- [ ] Setting on, **surround below Android 12L** (API 32) if such a device is available:
      must fall back to AudioTrack rather than opening a stream with an undefined layout.
- [ ] Under load — packet loss, decoder pressure — audio does not stutter. Two independent
      reports of unplayable stuttering exist against the implementation this replaces, so it
      is worth deliberately stressing.

> **What is now measured, and what still is not.**
>
> Measured on the Homatics Box R 4K Plus (Amlogic, API 34), and confirmed against
> `dumpsys media.metrics`: AAudio grants `LOW_LATENCY` and `EXCLUSIVE` on the first attempt,
> with a 384-frame burst and a 768-frame buffer — about 16 ms at 48 kHz — and ran a
> ~4-minute session with zero underruns. The callback thread is `SCHED_FIFO`. These are the
> numbers any future device gets compared against.
>
> **Shield TV baseline (API 30), which differs on every field worth naming.** AAudio grants
> `LOW_LATENCY` but is refused `EXCLUSIVE` — `low latency / shared` — with a 256-frame burst
> and a 512-frame buffer, about 10.7 ms at 48 kHz, and a 4096-sample ring. The callback thread
> is `SCHED_FIFO` here too (`policy=1` in `/proc/<pid>/task/<tid>/stat`; `chrt` needs root and
> will not tell you). So the Shield's target buffer is *smaller* than the Homatics' 768, and
> since `619a6b16` it actually holds that size for a whole session, with zero xruns — this box
> is the better of the two for audio output latency, not the worse.
>
> Two consequences of the Shield disconnecting its stream twice per session:
>
> - **"Absence is the healthy signal" does not hold on this box.** Two recoveries every session
>   means `clean` is never true, so the session summary is permanently `WARN` and the overlay's
>   failure row is permanently shown. A genuine problem is not distinguishable from the
>   baseline by log level alone here — read the counters, not the priority.
> - Expect ~0.7 s of silence and a few dozen dropped buffers at every stream start, before host
>   audio begins flowing. That is the transient, not a defect.
>
> Still unproven: the benefit **relative to** a broken AudioTrack. The problem this targets
> is AudioTrack's fast path being denied on certain Android TV devices, producing roughly
> 0.5–1 s of delay, and the Homatics does not exhibit that — its AudioTrack is granted
> `PERFORMANCE_MODE_LOW_LATENCY` too. So the feature is confirmed to work and to do no harm;
> what remains unconfirmed is how much it wins where AudioTrack fails.
>
> The Shield has not been measured with the setting **off**, so its AudioTrack path is still
> unknown — worth one session, since it is the only way to know whether AAudio buys anything
> on the box most likely to need it. Its AAudio stream does get the fast mixer
> (`AUDIO_OUTPUT_FLAG_FAST successful`), which suggests the platform is willing, but that says
> nothing about what `AndroidAudioRenderer` is granted.
>
> That gap is now narrower than it was. Both paths report their granted mode, so on an
> affected device (Google TV Streamer or similar) the comparison is a pair of log lines
> rather than an inference — and the AudioTrack line alone is enough to identify such a
> device without building anything.

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

## Hardware still needed

| Needed for | Hardware |
|---|---|
| §2 in full | Nintendo Switch Pro Controller + USB cable |
| §3 latency claim | Google TV Streamer, or another device with the AudioTrack fast-path bug |
| §3 surround | 5.1 or 7.1 output on the host |
| §4 pads | Xbox Series S/X pad, 8BitDo pad, PowerA Pro |
| §4 decoder | Amlogic-based Android TV device |
| §1 Win+Shift+Left | Dual-display host |
