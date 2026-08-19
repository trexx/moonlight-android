# Audio to the controller's headphone jack

**Working on hardware.** An Xbox One pad (PID `02dd`) over adapter `045e:02fe` on a Shield TV plays
the stream's audio through its headphone jack, and the TV falls silent while it does.

This file is chronological. The design notes came first, then a long stretch where it did not work
and four hypotheses were disproved on hardware, then the answer. All of it is kept: the disproved
leads are the reason the working version looks the way it does, and re-deriving them would cost
another round of builds. **"Measured against real hardware" onwards is the historical record — read
"What shipped" and "Known limitations" for the current state.**

The short version: audio is a *sub-device*, and a sub-device only appears after the GIP security
handshake completes. This driver had never authenticated, so no audio device had ever announced
itself on any transport. Implementing the handshake made it appear.

## The idea

Route the stream's audio to the 3.5 mm jack on an Xbox controller paired through the wireless
adapter, instead of (or as well as) the TV. Private listening without pairing a Bluetooth headset.

## It is feasible

This is not speculative. [xone](https://github.com/dlundqvist/xone) implements GIP audio and its
README lists **"Xbox One Stereo Headset (adapter or jack)"** among supported devices, so audio over
this protocol to the pad's jack demonstrably works.

xow — and therefore this driver — already declares the message types and implements neither:

```
CMD_AUDIO_CONFIG  = 0x08   // spec 3.2.5.1.2, format and volume
CMD_AUDIO_SAMPLES = 0x60   // spec 3.2.5.1.3/3.2.5.1.4, render and capture
```

## What would port, and what would not

xone's implementation splits cleanly.

**Portable** — the protocol half, in `bus/protocol.c`:

| Function | Purpose |
|---|---|
| `gip_suggest_audio_format` | negotiates the format with the device |
| `gip_set_audio_volume` | volume, including chat/render split |
| `gip_enable_audio`, `gip_init_audio_in/out` | the enable and init sequence |
| `gip_send_audio_samples` | outbound sample packets |
| `gip_handle_pkt_audio_format` / `_volume` / `_control` / `_samples` | the inbound side |

Packets go out every `GIP_AUDIO_INTERVAL`, which is **8 ms**.

**Not portable** — `driver/headset.c` (602 lines) is an ALSA device: `snd_pcm_*` callbacks and
hrtimer pacing. None of that has an Android analogue and all of it would be replaced by an audio
sink fed from the renderer.

## Latency

The reason this is interesting for this fork, and also the reason it is not a replacement for
anything.

- **Against Bluetooth**: 8 ms packets over the same 2.4 GHz link the input already uses should sit
  well inside Bluetooth's usual 100–200 ms penalty. For private listening this is very likely a
  clear win.
- **Against the current path**: it will not beat AAudio straight to the TV, which measured 147 ms
  better than AudioTrack on the Homatics (see `HARDWARE_TESTING.md` §3). This is an *alternative*
  output, and the existing path must be untouched when it is off.

Neither figure is measured for this route. They are the bounds it would have to land between to be
worth having.

## The format: 48 kHz, 16-bit

Reported from hardware rather than inferred, and it is the good answer.

**Moonlight decodes Opus at 48 kHz, so no resampling is needed.** That removes the largest unknown
and the most awkward part of the design — a resampler would have cost both latency and CPU on a
path that exists to save latency. 16-bit signed PCM is also what GIP audio uses throughout, matching
xone's `SNDRV_PCM_FMTBIT_S16_LE`, so the decoded buffer can go almost straight out.

The packet arithmetic falls out of it, at the protocol's 8 ms interval:

| | Samples per packet | Bytes | Within the 2048-byte Audio MTU |
|---|---|---|---|
| Mono | 384 | 768 | yes |
| Stereo | 384 | 1536 | yes |

Both fit, so **audio packets never need fragmenting** — worth knowing, because it keeps the audio
path clear of the chunk reassembly this driver just gained.

Both do, however, exceed 127 bytes, and spec 2.2.10.4 says the payload length field must then use
the extended encoding: seven bits per byte, bit 7 chaining to the next. `decodeVarint()` in
`gip.cpp` already implements it, but **`handlePacket()` only reaches it on the fragmented branch**.
An audio packet is not fragmented, so it would arrive with a multi-byte length down the fast path,
where the length is still read as a single byte. That path would need extending before audio
messages could be parsed. Harmless today — `CMD_AUDIO_SAMPLES` is never dispatched, so such a
packet is ignored rather than misread.

Since answered by the sub-device's own metadata: it advertises `09 10`, and the driver now proposes
whatever the device lists **first** rather than a hardcoded pair. The stereo row above is what that
resolves to in practice, so the arithmetic stands — but it is read from the device, not assumed.

## What shipped

Spread across the `gip/*` branch stack, because most of it is not about audio at all — the handshake
and sub-device routing are general GIP work that audio happens to be the first consumer of.

**`gip/security` — the reason any of this works.** The v1 (RSA) security handshake, per the capture
and xone, with the framing and timing taken from the specification:

- `HOST_HELLO` → `CLIENT_HELLO` → certificate (reassembled from fragments) → RSA-encrypted
  pre-master secret → `FINISH`, driven by acknowledgements rather than timers.
- Messages above 58 bytes are fragmented (§3.1.5.2), which the pre-master secret at 274 bytes
  requires. Getting this wrong cost two pad connect-loops; see the history below.
- Crypto lives in Java (`GipCrypto`), not mbedTLS. Android's providers already have SHA-256, HMAC
  and RSA, and enabling bignum/RSA/ECP in the mbedTLS config shared with `moonlight-core` would
  have grown both native libraries by a couple of hundred kilobytes for a handshake that runs once
  per connect. Nothing on it is near a hot path.
- The certificate is never validated — Microsoft's do not parse as RFC 5280, and there is no trust
  decision to make here.
- v2 (ECDH P-256, commands `0x21`–`0x27`) is **not** implemented. The data header's version byte
  discriminates, and a v2 pad logs "device wants protocol v2, which is not implemented" rather than
  failing obscurely.

**`gip/pad-audio` — the audio itself:**

- Sub-devices route. `handlePacket()` no longer discards device id > 0; the announce for a
  sub-device requests its metadata and must **not** reach `Controller::initInput()`, which assigns
  over a joinable `rumbleThread` and terminates the process.
- The §2.2.11 initialisation sequence, in order: `STOP` → propose the device's **first advertised**
  format pair → wait for the device to echo it → `START` → wait for the device's Volume message →
  stream. Sending samples before the echo produces silence.
- `handlePacket()` decodes an extended payload length on the unfragmented path too, and
  `encodeVarint()`/`encodeHeader()` build downstream headers at the required even length.
- `sendAudioSamples()` emits `0x60` packets with their own sequence counter, audio being a separate
  data class.
- `Controller` gains a bounded ring and a sender thread per pad, both existing only while that pad
  has audio on. It self-clocks: audio arrives from the host in real time, so waiting for a packet's
  worth of samples paces the sends without a timer.
- `PadAudioSink` fans out to at most **two** pads, and an empty target list *is* the fallback to
  local output, so a pad disconnecting needs no special case.
- Control is the in-game menu only — no preference. Which pad has headphones on it is a
  per-session choice, and it means audio starts off every session.
- Four counters — packets sent, bytes dropped, packets late, send failures — logged as a summary
  when audio is disabled and shown on the performance overlay while it is on. See "What it
  measures" below.

## What it measures

A verified session on the Shield, 4K60 stream, Xbox One pad over the adapter:

```
Audio session: 12816 packets sent, 0 bytes dropped, 179 late by >12ms, 0 send failures,
               last flow rate 1536
```

Over a 102.5 s streaming window that is **125.04 packets/s against an expected 125.00** — the 8 ms
cadence is being held almost exactly. Nothing dropped and nothing failed, so the ring never backed
up and no USB write was refused.

**What "late" means, and why 1.4% is the floor rather than a fault.** The counter fires when the
sender waits more than 12 ms for a packet's worth of samples. The feed and drain rates match
exactly — moonlight-common-c negotiates 5 ms Opus frames, so 960 bytes arrive every 5 ms, and
1536 bytes leave every 8 ms, both 192 000 B/s — but the *granularities* do not divide, so the wait
alternates on a 40 ms cycle:

| Packet | Ring when the sender wakes | Waits |
|---|---|---|
| 1 | 960, needs another feed | 10 ms |
| 2 | 384 + 960 = 1344, needs another | 5 ms |
| 3 | 768 + 960 = 1728 ✓ | 10 ms |
| 4 | 192 + 960 = 1152, needs another | 5 ms |
| 5 | 576 + 960 = 1536 ✓ | 10 ms |

Average 8 ms, and the worst case is 10 ms — inside the 12 ms threshold, so steady-state granularity
contributes nothing. The lates are the half of packets already waiting 10 ms that then meet more
than 2 ms of extra jitter, from network arrival or the decode thread being descheduled.

**Read the count against the frame duration.** If a stream ever negotiates **10 ms** Opus frames —
`SdpGenerator.c` does that for a slow decoder or a bitrate under the threshold — then four packets
in five wait a full 10 ms, leaving 2 ms of headroom instead of alternating. The same jitter would
then read as a far higher late count with the audio no worse. The threshold is tuned for the 5 ms
case this hardware actually negotiates.

An earlier version of this counter measured the ring being *empty* on wake, which is the normal
steady state by design — it reported 20% starvation through audio that was audibly perfect. A
metric that alarms on healthy behaviour is worse than no metric, so it was changed to measure time.

## Measured against real hardware — the historical record

> **Superseded.** Everything from here to "Known limitations" describes the state *before* the
> security handshake existed, and the conclusion it reaches — that no audio sub-device ever
> announces itself — was true only because this driver had never authenticated. It is kept because
> the measurements are sound and the disproved hypotheses are worth not repeating. The resolution
> is in "What shipped" above.

An implementation of the protocol half below was built and tested. It does not produce sound, and
the reason is structural rather than a coding mistake.

**The pad declares no audio at all.** On an Xbox One pad (PID `02dd`, integrated 3.5 mm jack) over
adapter `045e:02fe`, on a Shield TV, device 0's metadata lists exactly two data classes — `0x20`
(input) and `0x09` (rumble) — and **zero audio formats**. That dump is byte-for-byte identical
with and without a headset plugged into the jack.

**The send side was never the problem.** With audio enabled the driver transmitted 3125 packets in
25 s, a dead-steady 125/s at the protocol's 8 ms cadence, with correctly encoded headers
(`60 20 02 80 8c 00` — command, sequence, varint 1536, padded even). The pad never acknowledged
the format request and never sent a single Audio Capture message, so its flow rate stayed at 0.
It simply is not listening.

**Why: audio is a separate GIP client.** In xone, `client->audio_formats` is read only by
`driver/headset.c`, a driver bound to its own client; `driver/gamepad.c` has no audio path at all.
So the audio endpoint is **never** device 0 — integrated jack or old stereo-headset adapter alike.
`handlePacket()` discards every packet with device id > 0, so we could not see such a client even
if it appeared.

**No accessory client ever announces**, and that has now been tested rather than inferred. Debug
builds report every packet addressed to a device id above zero, at both places one could be lost -
the parser's accessory filter and the fragmented-message branch that runs ahead of it - plus the
wcid lookup above them, in case a headset were to associate as its own wireless client instead of
sharing the pad's. Across three conditions on an Xbox One pad (PID `02dd`) over adapter
`045e:02fe`, all three counters stayed at zero:

| Condition | Accessory packets |
|---|---|
| Headset connected before the pad powers on | 0 |
| Headset hot-plugged while connected and streaming | 0 |
| Audio config sent anyway, 24 s of transmission | 0 |

The metadata was byte-identical in all three: two data classes, `0x20` and `0x09`, no audio
formats. So lifting the accessory filter would achieve nothing on its own - there is nothing
behind it to let through. The same pad plays headset audio on Windows, so the hardware is capable
and the gap is in this driver.

**xone does support this over the adapter**, so a working reference exists. Its clients are created
on demand from the device id in each header — `gip_get_client(adap, hdr.options & GIP_HDR_CLIENT_ID)`
in `gip_process_buffer()` — which is transport-independent, and `transport/dongle.c` routes a
`GIP_BUF_AUDIO` buffer onto its own transmit queue. The audio *adapter* operations
(`enable_audio`, `init_audio_in`/`_out`) that only `transport/wired.c` registers are optional and
return success when absent: the wired path needs them to configure USB isochronous endpoints, and
the dongle needs no transport-level setup at all.

Two candidates for what we are missing, neither tested at the time. **The first was half right:** the
handshake was indeed the answer, but the link encryption bundled with it here turned out to be
unnecessary — see step 5 below. The second was wrong and was disproved on hardware.

- **Link encryption, and the handshake behind it.** This is the strongest lead. xone's dongle
  implements `set_encryption_key`, which programs a per-client key into the MT76
  (`xone_mt76_set_client_key(&dongle->mt, client->wcid, key, len)`), and the key comes from the
  security handshake in its `auth/` module. xow does neither — its own handshake comment lists
  `Authenticate` in both directions and marks it "unused" — and runs the link unencrypted. Input
  and rumble work that way; a pad may well withhold its audio client until authenticated.
- **The setup sequence is incomplete.** xone does `gip_suggest_audio_format` →
  `gip_set_audio_volume` → `gip_init_audio_out`/`_in` → `gip_enable_audio`; we send only the
  format. `gip_set_audio_volume` is a real GIP message we never send, and is cheap to try. The
  other three are the optional adapter operations above and are no-ops for the dongle.

Also worth noting as a divergence: `gip_process_buffer()` loops over *several* GIP messages in one
transport buffer, while `handlePacket()` parses only the first and ignores anything after it. The
frames observed here carried a single message plus a couple of bytes of alignment padding, so this
is not the cause of what is described above, but it is a real gap.

## What the specification says, and what this pad says back

[MS-GIPUSB] v20240916 settles several things this file previously guessed at.

**Audio is a separate GIP device, not a capability of the pad.** Table 1 shows a controller
exposing sub-device 1, "3.5 mm Audio", with its own derived device ID and its own metadata. It
announces itself 500-1000 ms after the primary device initialises (2.2.11). So the device-id filter
in `handlePacket()` was never the obstacle - there has to *be* a sub-device before there is anything
to route.

**Device 0 declares no audio, and that is expected rather than damning.** Its metadata carries an
empty `SupportedAudioFormats`, no command `8` (Audio Control) or `96` (Audio Data) in either
direction, and none of its interface GUIDs is `IHeadset` `{BC25D1A3-C24E-4992-9DDA-EF4F123EF5DC}`
or `ICustomAudio`. Its three GUIDs decode as `IController`, `IGamepad` and `INavigationController`:
precisely the specification's own worked example of a plain gamepad, which is a useful check that
the metadata parser is right.

**None of that says the pad lacks audio.** Audio is sub-device 1 with its own metadata, so a
primary device looks like this whether or not a headset jack exists behind it. An earlier revision
of this file read those signals as proof the pad had no audio at all; that was a conflation of the
primary device with the pad, and it is wrong.

**Windows settles it: the pad does have audio, over this adapter.** The same pad on the same
`045e:02fe` adapter makes an audio device appear on Windows:

```
USB\VID_045E&PID_02E4&IGA_00\00&0300001C94C28DED7E&09&16
USB\Class_01&Subclass_01&Prot_00
```

Class 01 / subclass 01 is USB Audio Class, AudioControl. The product ID is `02E4`, not the `02DD`
the pad announces over GIP - a separate ID for a separate device, as Table 1 describes - and the
instance string has the shape of a derived secondary DeviceID. So the sub-device is real, it is
reachable through the wireless adapter, and the reason it never appears here is something this
driver does or fails to do.

**The security exchange is not a gate.** Section 5: *"The host succeeds the security exchange by
default"*, and a controller may list an opt-out GUID (`7a34ce77-7de2-45c6-8ca4-0042c08bd94a`) to
skip it over USB. A port of xone's `auth/` module was planned as the last remaining explanation and
was dropped on reading this; the research is preserved below in case a pad that *does* advertise
audio still refuses.

**The handshake order was wrong, and is now fixed.** Set Device State: START was sent immediately
after the metadata request, roughly 60 ms before the response arrived, while 3.1.1 has the device
go Arrival -> Idle on the request and Idle -> Active on the state message. Since an audio sub-device
waits on the primary having initialised, that was worth correcting on its own terms. It is
corrected, and measured: metadata now precedes the serial number rather than following it. **It did
not produce a sub-device.**

**The audio initialisation sequence in 2.2.11 is not what this driver implements**, and would not
work even against a pad that had an audio device:

| Specification | This driver |
|---|---|
| Set Device State: **STOP** before configuring | never sent |
| Audio Control: Configuration using the device's **first** advertised format | hardcodes 48 kHz stereo |
| Device replies with the format it adopted; host retries up to 4x at 1 s on mismatch | reply never read |
| Set Device State: **START** | never sent |
| **Device sends** Audio Control: Volume, and the host need not play audio until it arrives | sent in the wrong direction, host to device |
| Render data only then | streamed immediately |

Anyone implementing this against hardware that does advertise audio should start from that table
rather than from what shipped.

## The pad's own USB descriptors, which settle feasibility

Cabling the pad to a Homatics Box R 4K Plus and enumerating it from Android - which can read
descriptors even though it cannot perform isochronous transfers - gives `045e:02dd` as five
interfaces:

```
interface 0 alt 0  class ff sub 47 proto d0 | ep 01 intr 64 @4ms | ep 81 intr 64 @4ms
interface 1 alt 0  class ff sub 47 proto d0                              (no endpoints)
interface 1 alt 1  class ff sub 47 proto d0 | ep 02 ISOC 228 @1ms | ep 82 ISOC 228 @1ms
interface 2 alt 0  class ff sub 47 proto d0
interface 2 alt 1  class ff sub 47 proto d0 | ep 03 bulk 64      | ep 83 bulk 64
```

**Interface 1 alternate 1 is the audio interface of 2.2.12**: vendor class `0xff`, GIP subclass
`0x47`, protocol `0xd0`, isochronous out on `0x02` and in on `0x82` at 1 ms. The specification
gives 228 bytes out and 64 in; this pad offers 228 both ways. Alternate 0 carries no endpoints and
is the idle setting to switch away from.

The three interfaces are the three sub-devices of Table 1 - primary, 3.5 mm audio, other - so the
sub-device model is not an abstraction here, it is visible in the hardware. **This pad has audio,
and it is laid out as documented.** Any remaining doubt about whether controller audio is possible
at all is settled; what is unresolved is only how to reach it.

Two things the same scan corrected:

- **Interface 0's endpoints are interrupt, not bulk** (type 3, 64 bytes, 4 ms). `AbstractXboxController`
  drives them with `bulkTransfer()`, which Android permits on an interrupt endpoint, but comments
  in that file describing them as bulk endpoints are wrong.
- The adapter on this box, `045e:02e6`, is one interface with eight 512-byte bulk endpoints, and
  differs from the `045e:02fe` adapter on the Shield. The driver treats them identically.

## Audio over a cable, as a separate route

Cabled audio is a different problem from the wireless one, and unlike it, entirely specified.

**Android cannot drive it.** `UsbDeviceConnection` offers control, bulk and interrupt only;
isochronous is not exposed by the framework at all. `XboxOneController` is therefore the wrong API
for this, not merely missing a feature.

**libusb can, and the pattern already exists here.** `XboxWirelessDongle` hands
`connection.getFileDescriptor()` to native code, which wraps it with `libusb_wrap_sys_device()`
(`dongle/usb.cpp`). libusb does isochronous, and it is already vendored and built.

The work, roughly in order:

1. A native USB transport - a single-device sibling of `Dongle` - wrapping the fd and pumping
   interface 0's interrupt endpoints.
2. Moving cabled pads onto `GipDevice`. `XboxOneController` is a minimal GIP implementation: canned
   init packets and two message types (`0x20` input, `0x07` guide). It has no metadata, status,
   chunk reassembly or device-state machine. `GipDevice` is already transport agnostic - it takes a
   `SendPacket` callback - so this inherits all of that, audio included.
3. `libusb_set_interface_alt_setting()` on interface 1, then a pool of isochronous transfers at
   228 bytes per millisecond. Note this is a different cadence from the wireless path's 1536 bytes
   per 8 ms, so the ring and sender thread would need to pace differently.
4. The 2.2.11 sequence, unimplemented on either route.

## A USB capture of the Windows host, which answers it

A USBPcap capture of the *same* pad (`7e:ed:8d:c2:94:1b`) through the *same* adapter
(`62:45:b5:05:12:c1`) on Windows, with the headset plugged in partway through. GIP unwrapped from
the MT76 and 802.11 framing, host-to-device marked `-->`:

```
31.207 <-- ANNOUNCE   dev=0   7e ed 8d c2 94 1b 00 00 5e 04 dd 02
31.224 --> METADATA   dev=0   (request)
31.247 --> ACK        dev=0   (chunked metadata)
31.317 --> SET_STATE  dev=0   00                       START
31.327 --> LED        dev=0   00 01 14
31.379 --> SECURITY   dev=0   00 41 00 01 00 2c 01 01 00 28 d3 dd ...
31.424 <-- SECURITY   dev=0   00 c1 00 01 00 00
31.456 --> SECURITY   dev=0   00 42 00 02 00 54 ...
31.504 <-- SECURITY   dev=0   00 c2 00 02 ...
31.582 --> SECURITY   dev=0   00 42 00 03 04 04 ...
31.689 <-- SECURITY   dev=0   30 82 03 2b 30 82 ...    an X.509 certificate
31.907 --> SECURITY   dev=0   00 41 00 05 01 04 ...    260 bytes
              ...
36.351 <-- ANNOUNCE   dev=3   7e ed 8d c2 94 1c 00 00 5e 04 e4 02
36.379 --> METADATA   dev=3
36.472 --> SET_STATE  dev=3   01                       STOP
37.481 --> AUDIO_CTRL dev=3   02 09 10                 format: in 0x09, out 0x10
37.507 <-- AUDIO_CTRL dev=3   02 09 10                 device echoes it
38.507 --> SET_STATE  dev=3   00                       START
38.533 <-- AUDIO_CTRL dev=3   03 84 b2 b2 e4 80 00 00  volume, device to host
38.539 <-- AUDIO_DATA dev=3   ...                      capture flowing
38.564 --> (1608-byte render packets begin)
```

**The security exchange happens, and this file previously said it does not.** Section 5's
*"The host succeeds the security exchange by default"* was read here as "the host skips it". It
means the host does not fail a device over the result. Ninety security messages say otherwise, and
a planned port of xone's `auth/` module was cancelled on that misreading.

Decoded against xone's structures the exchange matches message for message: `context=00`,
`options=41` (ACME | from host), `error=00`, `command=01` HOST_HELLO, big-endian length `0x2c`,
then a data header of `command=01, version=01, length 0x28`. Then `0x02` CLIENT_HELLO requested,
`0x03` CLIENT_CERTIFICATE with length `0x0404`, the certificate itself, and `0x05` HOST_SECRET at
260 bytes - a 256-byte RSA-encrypted pre-master secret. **This is xone's v1 RSA path exactly.**

**The audio sub-device is device id 3**, announcing VID `045e` PID `02e4` with MAC
`7e:ed:8d:c2:94:1c` - the pad's address plus one, the derived secondary ID of 1.3 - and matching
the `PID_02E4` Windows Device Manager shows. It appears about five seconds after the handshake,
when the headset is plugged in.

**Two things this validates in the current driver.** Windows does metadata request, metadata,
Set Device State: START, LED - which is the order this driver was corrected to, so we now match the
real host. Its LED brightness is `0x14`, the same value xow uses.

**Two things it corrects in what shipped.** The format pair is `in=0x09, out=0x10`, not the
`0x10/0x10` hardcoded here; the host takes the device's first advertised pair. And Audio Control:
Volume travels device to host - it was sent the wrong way in an earlier experiment.

**And the specification states the mechanism outright.** 2.2.1.4, under Secondary Device ID:

> *"GIP supports enumeration of additional sub-devices after the primary device has completed the
> Security Handshake successfully."*

So this is not a correlation drawn from one capture. Sub-device enumeration is specified to follow
a successful handshake, which is why no `dev=3` has ever appeared here: this driver has never
authenticated. The capture shows the same thing happening in practice, and the pad lists command
`6` in both capability arrays.

Worth noting where that sentence was found, because it was nearly missed: under *Device IDs*,
not under Security. Section 5 covers only whether the host enforces the exchange, and the
specification documents every message type in 3.1.5.5 **except** this one - there is no mention
anywhere of certificates, pre-master secrets, public keys or session keys. The omission looks
deliberate. So the handshake's payload has to come from the capture and from xone, while
everything around it - that it gates sub-devices, that it is a Unique sequence pool, when in the
state machine it belongs - is in the specification and was worth searching for properly.

## The plan that resolved it

Kept with each step's outcome. The order was deliberately diagnosis-first: building more of the
protocol against a device that announced no audio client would only have repeated the result above.
That discipline is what eventually pointed at the handshake instead of at the sender.

1. ~~Find out whether an accessory client announces.~~ **Done — it does not**, under any of the
   three conditions in the table above, nor after the handshake ordering was corrected. The
   reporting lives in the driver behind `_DEBUG`, so this is re-checkable on another pad without a
   further build, and a pad that *does* expose a headset would show up in the same lines.
0. ~~Confirm on Windows what works and over which transport.~~ **Done: it works over this very
   adapter**, appearing as a USB Audio Class device with its own product ID. So the sub-device is
   real and the link carries it. The question is no longer whether it is possible but what the
   Windows host sends that this driver does not.

   **The next step that would actually answer that is a USB capture on Windows** - USBPcap or
   equivalent on the adapter, while a pad with a headset connects. That yields the exact GIP
   exchange that brings the audio device up, to diff against what this driver sends. Every
   alternative is guesswork at one build cycle per guess, and three such guesses have now been
   wrong.
2. ~~Try the missing setup messages.~~ **Done — no effect.** `gip_set_audio_volume` was implemented
   (`CMD_AUDIO_CONFIG` subcommand `0x03`, `mute=0x04` unmuted, out/chat/in = 100/50/100, xone's own
   values) and sent between the format request and the first samples. It transmitted without error
   and produced nothing: no acknowledgement, no flow rate, no accessory client, across 35 s of
   audio. The code was not kept — xone guards that call with `if (client->id && …)`, so it sends
   volume only to an accessory client and never to device 0, which makes sending it to the pad
   itself speculative and it is now also known to be useless. `gip_init_extra_data`'s undocumented
   `0x4d`/`07 00` remains untried, but has no more reason behind it than "xone sends it".
3. ~~Port the security handshake.~~ **Done, and this was the answer.** The capture showed Windows
   performing it in full with the audio sub-device appearing afterwards, so it was no longer the
   speculative last resort it had been when it was cancelled - and it was cancelled on a misreading
   of section 5, which says the *host* succeeds the exchange by default. That was read as "the
   handshake does not happen"; the capture disproved it.

   With the handshake in place a `dev=3` announce (VID `045e`, PID `02e4`) appears within a second
   of the pad initialising, exactly as Table 1 and §2.2.1.4 describe, and the §2.2.11 sequence
   brings it up.

   It is still a large piece of work: xone's `auth/` module is roughly 900 lines of crypto and
   certificate handling. What has changed is that it no longer has to be built blind. The capture
   is ground truth for every message, so each stage can be diffed against a known-good exchange
   rather than guessed at, and a wrong turn shows up immediately instead of after a build cycle.

   Two practical notes. The capture uses the v1 RSA path, so ECDH is not needed to reproduce it.
   And the certificate never needs validating - xone scavenges the public key out of the DER by
   pattern, because Microsoft's certificates do not parse as RFC 5280 - so no root of trust is
   involved.

   The dongle transport also implements `set_encryption_key` in xone, programming a per-client key
   into the MT76 (`xone_mt76_set_client_key(&dongle->mt, client->wcid, key, len)`) from the session
   key the handshake derives. xow does none of it and runs the link unencrypted, which is evidently
   enough for input and rumble but may well be why nothing else appears.
4. ~~Only then port the rest of the protocol half.~~ **Done.** It is off unless chosen from the
   in-game menu each session, and an empty target list is the fallback, so the existing path is
   untouched when it is off.
5. **Measure.** Partly done: the throughput and health numbers are under "What it measures" above,
   and the sender is holding cadence. **The latency claim this feature exists for — that it beats a
   Bluetooth headset by enough to matter — is still unmeasured.** `HARDWARE_TESTING.md` §10 has the
   checks. Take numbers from the end-of-stream summary rather than the overlay, per `CLAUDE.md`.

   Link encryption was **not** needed. xone programs a per-client key into the MT76 from the
   session key, and the working assumption here was that a pad might withhold audio until the link
   was encrypted. It does not: the handshake alone is sufficient, and this driver still runs the
   link unencrypted. The key-programming step in the plan was therefore never built, which also
   avoided its main risk — a wrong key silences the pad entirely, input included.

## The host does not send silence, and that is why the cushion has to rebuild

Measured on the Shield, 2026-08-19, against Turk-PC:

```
Received first audio packet after 11500 ms      (PC idle)
Received first audio packet after 100 ms        (music playing)
```

**The host sends no audio packets at all when there is no sound** — not silent packets, nothing.
So the supply to the pad is inherently bursty, and the ring running dry is normal operation rather
than a fault. Any design here has to survive it.

That is what makes one-shot priming untenable, and the numbers show it plainly. Three sessions,
same pad, same box:

| Session | Supplied | Drained | Prime events |
|---|---|---|---|
| Old build, fresh, audio playing | 181 kB/s (94%) | 943 pkt/s | 1 |
| Old build, relaunch, mostly-idle PC | **43 kB/s (23%)** | 226 pkt/s | 6 |
| New build, relaunch, audio playing | **192 kB/s (100%)** | 999.6 pkt/s | 2 |

The 23% session was not a fault: the PC was quiet for most of it, so that is simply how much audio
existed. The fault was what the old build did with it — the first dry spell latched the transport
into "primed against an empty ring" permanently, and every packet after that was padded with
silence. A quiet stretch therefore did not cost a quiet stretch; it cost the rest of the session.

Read the supply figure before blaming anything: `bytes queued` divided by the time audio was
enabled, against 192000 B/s. Below that, the host was silent and the pad is reproducing what it was
given.

## Known limitations

- **No rate adaptation, which is the anti-pop mechanism.** Each upstream Audio Capture message
  carries a flow rate — "the number of bytes of render data the host SHOULD send in each message"
  (MS-GIPUSB Table 69) — and §3.2.5.1.5 is explicit that modulating the render size against it "is
  the mechanism GIP devices use to eliminate pops and clicks in audio". A render-only device still
  sends capture messages purely to drive it.

  We send a fixed 1536 bytes and ignore it. xone does the same, so this is not worse than the
  reference, but it is declining a mechanism the protocol provides rather than merely risking
  drift. Implementing it means draining the ring by the requested count instead of a constant —
  not difficult, and the obvious next step if pops are heard.

  Note the device is *expected* to nudge the value about: ±1 sample per channel per ms, which over
  an 8 ms packet is ±32 bytes. Movement inside that band is the protocol working. Only a request
  sitting well outside it is logged.

  **The units are now verified for this transport: whole-buffer.** This was open while no pad had
  ever reported a rate. A working session reports **1536**, exactly the 8 ms packet size we send,
  confirming xone's assumption and ruling out the spec's per-1 ms worked examples (192 bytes for
  48 kHz stereo) as the unit here. The rate is reported on the overlay and in the session summary,
  so a device that ever disagrees will be visible rather than silently mismatched.
- **Stereo 48 kHz only.** Samples are forwarded verbatim with no downmix, so a surround stream
  disables the feature rather than sending something wrong.
- **A pad left streaming by a killed process is heard until the cable is pulled.** See
  "Audio over a cable" below; it is understood rather than mysterious, and exiting cleanly avoids
  it. What survives is the previous run's stream, heard as repeating noise the moment a new stream
  starts, before audio has been enabled at all.

  Degraded playback across sessions used to be listed here as well, and that part was ours: the
  ring's cushion was primed once and never rebuilt. It is fixed, and the two are worth keeping
  apart — this one needs the cable pulled, that one did not.

  A pad whose sub-device declares no usable format is still refused with a message saying so,
  rather than moving the stream's audio off the TV into silence.
- **Two pads is untested with real audio.** `PadAudioSink` caps at two and the fan-out is written,
  but only one pad has ever had a headset on it here. The second pad's ring and sender thread are
  the same code, so the risk is contention on the link rather than logic.
- **Format renegotiation is not implemented.** The driver proposes the device's first advertised
  pair and expects the echo to match. The specification allows up to four attempts; a device that
  counter-proposes something else is not handled, and would simply never reach streaming.
- **v2 (ECDH) security is not implemented**, so a pad that wants it authenticates not at all and
  therefore has no audio. It is detected and logged rather than failing silently. No v2 hardware
  has been available to test against.
- **No microphone.** There is no mic support anywhere in this client, so the capture direction is
  negotiated but never read.

## Audio over a cable, as it was actually built

Working on hardware: an Xbox One pad on a USB cable plays the stream's audio through its headphone
jack, over the same GIP stack, handshake and volume control as the adapter.

**Interface 0 cannot carry it.** 64 bytes every 4 ms is 16 KB/s against the 192 KB/s that 48 kHz
stereo needs, so §2.2.12's isochronous endpoints are the only path with the bandwidth — settled by
arithmetic, not experiment. But interface 0 must still be ours, because the audio sub-device only
exists after the security handshake and the handshake runs there. Leaving input to Android and
claiming only interface 1 is therefore not an option.

**Each isochronous packet is one GIP message carrying one millisecond**: 192 bytes of samples behind
a 6-byte header, 198 inside the endpoint's 228. The bus consumes exactly one packet per frame, so
that is fixed rather than chosen.

Five things were got wrong and are worth not repeating:

- **Packets are packed contiguously by their declared length**, not at a fixed stride. Writing each
  message at the maximum stride while declaring a shorter length makes every packet after the first
  read from the wrong offset. It is heard as noise, and then as the device leaving the bus.
- **The bus pulls; the host does not push.** Submitting only when samples happen to be ready leaves
  the endpoint unfed whenever the host's clock is the slower of the two, and a packet never
  submitted is not a packet that failed — the per-packet status reports nothing. Transfers now stay
  in flight and are refilled from the ring on completion.
- **A pull needs a cushion.** Draining the ring to empty on every completion means any millisecond
  the host is late becomes silence, and the ring never recovers. Real audio interleaved with silence
  does not sound like a dropout; it sounds slowed down. Silence is sent deliberately until a
  cushion accumulates.
- **The cushion has to be rebuilt, not just built.** Priming was a start-up step, done once when the
  transport came up, and the cushion only ever shrinks after that: a shortfall is padded with
  silence and nothing puts the missing milliseconds back. So one late millisecond left the rest of
  the session playing from a ring hovering at empty.

  It bites hardest between sessions, which is how it was found — audio gapped and slightly slowed
  on every session after the first, cleanly reproducible by disconnecting and reconnecting.
  Disabling audio deliberately leaves the stream up carrying silence, so the ring is simply not fed
  and drains flat; re-enabling takes the "resuming into the running stream" path, which never
  re-enters the transport and so never re-primes. Every session after the first therefore ran with
  no cushion at all, from its first sample.

  **It is a regression, and the commit that introduced it is identifiable.** "Stop cycling the audio
  interface on every session" measured three degrading sessions in one process with healthy numbers
  throughout — 192.2 bytes queued per packet sent, zero underruns, the ring never dry — and
  concluded the pipeline was exonerated. That was true of the code as it stood: every session still
  went through `enableAudio()`, so every session re-primed. The commit immediately after it,
  "Configure the pad's audio once, as the spec and xone both do", added both early returns — the
  disable that leaves the stream up and the enable that resumes into it — and from that point no
  session but the first ever primed. The measurement was never repeated afterwards, so it went on
  being cited for code it no longer described.

  Worth keeping as the shape of the mistake rather than the mistake itself: an exoneration is only
  good for the code it was measured on, and this one outlived it by one commit.

  `submitAudioTransfer()` now re-arms whenever it finds the ring completely empty, so a collapse
  costs one prefill of deliberate silence and then plays with a cushion again. The transport's
  counters are reset on the resume path too — they were not, so a resumed session inherited the
  previous one's totals plus a gap counted for every millisecond audio had been off, which would
  have made this unreadable in the very summary that should show it.
- **`handlePacket()` is not reentrant.** It owns the chunk reassembly buffer and the sequence
  counters and is only ever entered from the interrupt read thread. Reading the capture endpoint
  through it puts the libusb event thread inside it as well, a thousand times a second, and
  corrupts its state — the crash lands somewhere unrelated. The capture path decodes the flow rate
  itself.

**Rate adaptation is implemented, and is what §3.2.5.1.5 calls "the mechanism GIP devices use to
eliminate pops and clicks".** The device asks for 188, 192 or 196 bytes per millisecond in the flow
field of its Audio Capture messages, which arrive on the isochronous IN endpoint. Nothing read that
endpoint at first, so the flow rate logged as zero and a fixed 192 went out regardless. The samples
themselves are discarded; this client has no microphone.

**The device is configured once and never renegotiated.** This is the rule to keep. §2.2.11 has
audio "flow continually even if the data represents only silence", and xone configures at
`gip_headset_probe()` and never again for the life of the client — neither ever asks a device to be
reconfigured. Doing it per session degraded the pad a step each time, first session clean and each
one after it worse, while our own side measured perfect throughout: 192.2 bytes supplied per packet
sent, 999 packets a second, zero underruns. Enabling and disabling now only decides whether the ring
is fed.

### The one limitation, and why it stays

A pad left streaming by a process that was *killed* plays degraded until the cable is pulled.

§2.2.11 keeps a started audio device streaming until it is powered off, disconnected, or told to
stop — and Android closes the USB connection before any teardown of ours runs, so the stop fails
with `NO_DEVICE`. A new process cannot undo it either. All of these were tried on hardware and none
worked:

**What separates a good session from a bad one is one packet.** A clean session opens with the
sub-device announcing itself; a stuttering one goes straight to metadata with no announce. Five
sessions on the Shield, and the correlation is exact - announce present, audio clean; announce
absent, audio stutters - while our own counters read 100% supply, bus-rate drain and no underruns
in both. §2.2.1 has a device send Hello only while in Arrival, so a sub-device left Active by a
killed process never announces, and everything we then send is addressed to the previous run's
stream. `Audio device N offers ... announced yes|no` is logged at discovery, so a session that will
stutter says so before anyone listens to it.

**A sub-device RESET does not bring it back, and this is now tested rather than assumed.** §3.1.1
has a device reinitialise "as it does on power up" on RESET and then "send GIP Hello's at 500 ms
intervals until the host responds", which is precisely the announce we are missing. This pad
answers with silence: no hello, ever, and therefore no audio device for the rest of the session.
Driving setup from the hello turned stuttering audio into "no headset detected", and was reverted.

| Attempt | Result |
|---|---|
| Set Device State: STOP on discovery and on teardown | No effect |
| Set Device State: RESET to the audio sub-device | No effect |
| RESET, then waiting for the hello §3.1.1 promises | **No hello, ever - loses audio entirely** |
| Proposing the device's other format first, so the real one is a change | Confirmed to run, no effect - **and withdrawn**, see below |
| Set Device State: RESET to the primary device | Takes the pad off the USB bus; permission prompt loop |
| `libusb_reset_device()` to re-enumerate, as xone does at probe | Pad left unclaimed, **input dead**, USB stack cycling |

The last is the important one. Re-enumeration is what pulling the cable does and what xone does on
every probe, but through a wrapped descriptor on Android it is not equivalent, and it cost input.
`XboxWiredGipController.resetIfPreviousSessionUnclean()` is kept unused as the record of that.

Exiting cleanly — disabling audio, or disconnecting from the menu — avoids the whole thing.

**The alternate-format proposal is withdrawn.** It was the third row of that table: propose the
device's *second* advertised pair on discovery so that the real one registers as a change rather
than a repeat. It did not fix the fault, was recorded as such, and was left in the code anyway —
which quietly made it a renegotiation on every connect, against the one rule this file establishes
from hardware: configure once, never again.

What made it worth removing rather than merely tidying is what the two pairs are. This pad
advertises `09 10` and `09 09`, and §3.2.5.1.2 gives `0x09` as **24 kHz mono** against `0x10`'s
**48 kHz stereo**. So every startup retuned the render path to 24 kHz mono and back to 48 kHz
stereo a few seconds later, with §2.2.11 requiring the device to reconfigure its audio hardware
before it answers each one. A render pipeline left anywhere between those two plays gapped and an
octave low — which is the reported symptom, arrived at from the wrong direction.

That is a mechanism, not a proof: the fault also appears on the leftover-stream path, and both were
in play at once. It is removed because the specification says not to do it and because it was known
not to help, which is enough on its own.

## Still to measure

Throughput and stream health are answered above. What is not:

- **Whether audio costs input latency**, since both share the 2.4 GHz link. This is the one result
  that decides whether the feature is worth using, and it is the reason the fork exists. Nothing
  about a healthy 125 packets/s speaks to it — the sender keeping cadence says the audio is
  arriving, not that input is unaffected by sharing the link with it.
- **Whether it beats a Bluetooth headset by enough to matter**, which is the claim in "Latency"
  above and still an estimate on both sides.
- **Audible quality over a long session.** 179 late packets in 102 s did not produce anything
  audible, but no one has listened for pops across, say, an hour. If they appear, rate adaptation
  is the mechanism the protocol provides and is the first thing to implement.
- **Whether re-priming ever thrashes.** An empty ring now costs a full prefill of deliberate
  silence rather than one padded millisecond, which is the right trade for a collapse and the wrong
  one for a stream that merely runs close to empty. Nothing measured says this hardware does, and
  the underrun counter is where it would show.

`HARDWARE_TESTING.md` §10 has the checks for all three.
