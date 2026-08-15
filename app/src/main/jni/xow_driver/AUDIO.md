# Audio to the controller's headphone jack

Assessment only. **Nothing here is implemented.** Written up so the research does not have to be
done twice, and because the format question that gated it has now been answered.

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

## Next step

1. Port the protocol half above — format negotiation, volume, enable/init, and the sample sender —
   behind a setting that is **off by default**, the same shape as the AAudio work, which degrades
   to the existing path rather than replacing it.
2. Extend `handlePacket()` to decode an extended payload length on the unfragmented path, which
   audio needs and nothing else currently does.
3. Feed it from the same decoded PCM the existing renderer consumes, without disturbing that path
   when the setting is off.
4. Measure. The claim worth testing is the one this is for: that it beats a Bluetooth headset by
   enough to matter. Take numbers from the end-of-stream summary rather than the overlay, per
   `CLAUDE.md`.

`HARDWARE_TESTING.md` §8 still has a table for what each pad reports; filling it in for more than
one generation is worth doing, since the format above is confirmed for one pad rather than all.
