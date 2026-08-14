# Audio to the controller's headphone jack

Assessment only. **Nothing here is implemented**, and the next step is a measurement rather than
a commit. Written up so the research does not have to be done twice.

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

## What blocks it

**The audio format has to be discovered, not guessed.** GIP devices declare `SupportedAudioFormats`
in their metadata (spec 2.2.2.4.3), which is exactly the element xone's `gip_parse_audio_formats`
reads. Metadata now arrives — fragment reassembly and the request landed alongside this note — but
nothing has yet reported what it says.

The format matters concretely. xone's `enum gip_audio_format` tops out at `48KHZ_STEREO`, and
Moonlight decodes Opus at 48 kHz. **If a pad reports a 48 kHz format, no resampling is needed**; if
it reports 24 kHz, a resampler joins the design. That single line of logcat changes the shape of
the work, so it is worth having before starting.

## Next step

1. Run the §8 checks in `HARDWARE_TESTING.md` and fill in the `audio formats` column for whatever
   pads are to hand.
2. Decide from that whether the sink needs resampling.
3. Only then port the protocol half above, behind a setting that is off by default — the same shape
   as the AAudio work, which degrades to the existing path rather than replacing it.

Until step 1 has real output, any implementation would be built on a guess about the one thing the
device is willing to tell us.
