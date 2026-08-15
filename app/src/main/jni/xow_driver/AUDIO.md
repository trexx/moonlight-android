# Audio to the controller's headphone jack

**Implemented.** This was the assessment that preceded it; the design notes are kept because they
explain *why* the implementation looks the way it does. What shipped, and what is still open, is at
the bottom.

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

Still open: whether the pad wants mono or stereo, which its metadata reports as a two-byte entry
per supported format. That decides only the packet size above, not the shape of the work.

## What shipped

- `handlePacket()` decodes an extended payload length on the unfragmented path too, and
  `encodeVarint()`/`encodeHeader()` build downstream headers at the required even length.
- `GipDevice::setAudioFormat()` negotiates 48 kHz stereo; `sendAudioSamples()` emits `0x60` packets
  with their own sequence counter, audio being a separate data class.
- `Controller` gains a bounded ring and a sender thread per pad, both existing only while that pad
  has audio on. It self-clocks: audio arrives from the host in real time, so waiting for a packet's
  worth of samples paces the sends without a timer.
- `PadAudioSink` fans out to at most **two** pads, and an empty target list *is* the fallback to
  local output, so a pad disconnecting needs no special case.
- Control is the in-game menu only — no preference. Which pad has headphones on it is a
  per-session choice, and it means audio starts off every session.

## Measured against real hardware, and it does not work yet

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

**But no accessory client ever announced**, with or without a headset connected, so lifting that
filter is necessary and not sufficient. The same pad plays headset audio on Windows, so the
hardware is capable and the gap is in this driver.

Two candidates, neither tested:

**xone does support this over the adapter**, so a working reference exists. Its clients are created
on demand from the device id in each header — `gip_get_client(adap, hdr.options & GIP_HDR_CLIENT_ID)`
in `gip_process_buffer()` — which is transport-independent, and `transport/dongle.c` routes a
`GIP_BUF_AUDIO` buffer onto its own transmit queue. The audio *adapter* operations
(`enable_audio`, `init_audio_in`/`_out`) that only `transport/wired.c` registers are optional and
return success when absent: the wired path needs them to configure USB isochronous endpoints, and
the dongle needs no transport-level setup at all.

Two candidates for what we are missing, neither tested:

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

## Next step

The order below is deliberately diagnosis-first. Building more of the protocol against a device
that announces no audio client only repeats the result above.

1. **Find out why no accessory client announces.** Lift the `deviceId > 0` filter in
   `handlePacket()` behind a debug log first and watch what, if anything, arrives when a headset
   is plugged in. That is one build cycle and it decides everything after it.
2. If nothing announces, **try the missing setup messages** — `gip_set_audio_volume` above all,
   since it is the one real GIP message xone sends that we do not.
3. If that changes nothing, **the security handshake is the remaining lead**, and it is a large
   piece of work: xone's `auth/` module is crypto plus certificate handling, and porting it is its
   own project rather than a step in this one.
4. Only then port the rest of the protocol half described above, behind a setting that is **off by
   default**, degrading to the existing path rather than replacing it.
5. Measure. The claim worth testing is the one this is for: that it beats a Bluetooth headset by
   enough to matter. Take numbers from the end-of-stream summary rather than the overlay, per
   `CLAUDE.md`.

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

  **The units are unverified for this transport.** The spec's worked examples are per-1 ms USB
  messages (192 bytes for 48 kHz stereo); we send one 8 ms message of 1536. xone initialises its
  expected rate to the whole 8 ms buffer, so it assumes whole-buffer units, but what a
  dongle-attached pad actually reports has not been observed. The log answers it.
- **Stereo 48 kHz only.** Samples are forwarded verbatim with no downmix, so a surround stream
  disables the feature rather than sending something wrong.
- **Integrated jacks only.** A headset on the old stereo-headset *adapter* is a GIP accessory with
  device id > 0, and `handlePacket()` discards accessory packets outright.
- **No microphone.** There is no mic support anywhere in this client, so the capture direction is
  negotiated but never read.

## Still to measure

Whether audio costs input latency, since both share the 2.4 GHz link — the one result that decides
whether this is worth using. `HARDWARE_TESTING.md` §9 has the checks.
