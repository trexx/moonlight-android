package com.limelight.binding.audio;

import com.limelight.LimeLog;
import com.limelight.binding.input.driver.XboxWirelessController;

/**
 * Routes decoded audio to the headphone jacks of Xbox pads on the wireless adapter.
 *
 * <p>Held by {@link LowLatencyAudioRenderer}, which consults it on every frame: while any pad is
 * enabled the audio goes there <em>instead</em> of the TV, and when none is it goes to the TV as
 * usual. That is deliberately the only fallback mechanism — an empty target list <em>is</em> the
 * fallback, so a pad disconnecting mid-session needs no special handling beyond dropping out of
 * the list.
 *
 * <h2>Why the cap</h2>
 * Two pads is what the adapter's link carries alongside stereo game audio and chat, so
 * {@link #MAX_TARGETS} is a bandwidth budget rather than an arbitrary limit. Audio shares the
 * 2.4 GHz link with controller input, and this fork cares more about input latency than about
 * audio, so the ceiling is enforced rather than advisory.
 *
 * <h2>Threading</h2>
 * {@link #getTargets()} is read once per audio frame from Moonlight's decode thread, so it must
 * not lock. Membership changes publish a whole new array rather than mutating the live one, which
 * makes a read a single volatile load and leaves an in-flight frame working on a consistent
 * snapshot.
 */
public class PadAudioSink {
    /** The adapter has bandwidth for two pads with stereo audio; see the class comment. */
    public static final int MAX_TARGETS = 2;

    private static final XboxWirelessController[] NONE = new XboxWirelessController[0];

    // Replaced wholesale, never mutated in place. See the threading note above.
    private volatile XboxWirelessController[] targets = NONE;

    // Whether the negotiated stream format is one a pad can take at all; see setStreamFormat()
    private volatile boolean formatSupported;

    /**
     * Headphone volume for every pad taking audio, 0 - 100.
     *
     * <p>Held here rather than per pad so the level survives a pad being switched off and on, or a
     * second pad joining — the user set a listening level, not a property of one controller. It is
     * a session value with no preference behind it, matching the enable toggle it sits next to.
     */
    private volatile int volumePercent = DEFAULT_VOLUME;

    // Whether a level has been chosen this session. Until one has, the pads keep their own.
    private volatile boolean volumeChosen;

    /** Only a fallback for "no pad has said otherwise yet" — pads report their own level. */
    public static final int DEFAULT_VOLUME = 100;

    /**
     * Records whether this stream's audio can go to a pad.
     *
     * <p>GIP tops out at 48 kHz stereo, and the samples are forwarded verbatim — there is no
     * downmix here. A 5.1 stream sent as though it were stereo would be noise, so a format the
     * pad cannot take disables the feature outright rather than producing something wrong.
     */
    public void setStreamFormat(int channelCount, int sampleRate) {
        formatSupported = channelCount == 2 && sampleRate == 48000;

        if (!formatSupported) {
            LimeLog.info("Pad audio unavailable for this stream: " + channelCount +
                    " channels at " + sampleRate + " Hz, need stereo at 48000 Hz");
        }
    }

    /** @return true if this stream's format can be sent to a pad at all */
    public boolean isFormatSupported() {
        return formatSupported;
    }

    /**
     * @return the headphone volume actually in force, 0 - 100.
     *
     * <p>Read back from the pad rather than assumed until a level has been chosen. A pad reports
     * its own setting once audio starts — 80% on the one tested here — and reporting a nominal 100
     * instead made the menu claim a level that had never been applied, so choosing "100%" audibly
     * raised the volume from a figure already displayed as 100.
     */
    public int getVolume() {
        if (volumeChosen) {
            return volumePercent;
        }

        XboxWirelessController[] pads = targets;

        // Any pad will do: the level is a session-wide choice, so they are only ever apart before
        // one has been made, which is exactly this branch.
        return pads.length > 0 ? pads[0].getAudioVolume() : volumePercent;
    }

    /**
     * Sets the headphone volume on every pad currently taking audio, and on any that joins later.
     *
     * <p>Synchronized with {@link #enable} so a pad cannot be added between the level being stored
     * and it being applied, which would leave that one pad at the wrong volume until the next
     * change.
     */
    public synchronized void setVolume(int percent) {
        volumePercent = Math.max(0, Math.min(100, percent));
        volumeChosen = true;

        for (XboxWirelessController target : targets) {
            target.setAudioVolume(volumePercent);
        }
    }

    /**
     * @return a line for the performance overlay, or an empty string when no pad is taking audio.
     *
     * <p>Called once a second, and only while the overlay is visible — the decoder does not build
     * its text otherwise. The common case costs a single volatile read and returns immediately,
     * so a session with no pad audio pays nothing at all; a session with one pays a JNI call a
     * second, off both the frame path and the audio path.
     *
     * <p>Totals rather than rates. Packets should climb by about 125 a second, one per 8 ms, and
     * dropped and late should not climb at all — a number that starts moving is the signal, which
     * an averaged figure would smooth away.
     */
    public String getOverlayText() {
        XboxWirelessController[] pads = targets;

        if (pads.length == 0) {
            return "";
        }

        StringBuilder text = new StringBuilder();

        for (int i = 0; i < pads.length; i++) {
            int[] stats = pads[i].getAudioStats();

            if (stats == null || stats.length < 5) {
                continue;
            }

            text.append("\nPad audio ").append(i + 1).append(": ")
                    .append(stats[0]).append(" pkts, ")
                    .append(stats[1]).append(" dropped, ")
                    .append(stats[2]).append(" late, ")
                    .append(stats[3]).append(" failed, flow ")
                    .append(stats[4]);
        }

        return text.toString();
    }

    /** @return the pads currently receiving audio; empty means audio belongs to the TV */
    public XboxWirelessController[] getTargets() {
        return targets;
    }

    /** @return true if at least one pad is receiving audio */
    public boolean hasTargets() {
        return targets.length > 0;
    }

    /** @return true if this pad is currently receiving audio */
    public synchronized boolean isEnabled(XboxWirelessController controller) {
        for (XboxWirelessController target : targets) {
            if (target == controller) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if this pad can take audio at all. Distinct from {@link #canEnableMore()}:
     *         that is about the bandwidth budget, this is about the hardware, and the two
     *         refusals need different explanations to be any use to the user.
     */
    public boolean isSupportedBy(XboxWirelessController controller) {
        return formatSupported && controller.hasAudioSupport();
    }

    /** @return true if another pad could be enabled right now */
    public synchronized boolean canEnableMore() {
        return formatSupported && targets.length < MAX_TARGETS;
    }

    /**
     * Adds a pad, negotiating the audio format with it first.
     *
     * @return false if the cap is reached or the pad refused, in which case nothing changed
     */
    public synchronized boolean enable(XboxWirelessController controller) {
        if (isEnabled(controller)) {
            return true;
        }

        if (!formatSupported) {
            return false;
        }

        // Checked before the cap so a pad that simply cannot do audio is not reported as having
        // hit the two-pad limit
        if (!controller.hasAudioSupport()) {
            LimeLog.info("Not enabling pad audio: controller declares no audio format");
            return false;
        }

        if (targets.length >= MAX_TARGETS) {
            LimeLog.info("Not enabling pad audio: already at the " + MAX_TARGETS + " pad limit");
            return false;
        }

        if (!controller.setAudioEnabled(true)) {
            LimeLog.warning("Pad refused audio");
            return false;
        }

        // Only if a level has been chosen. Otherwise the pad keeps whatever it came up at, which
        // is what getVolume() then reports - overriding it uninvited is what made the menu lie.
        if (volumeChosen) {
            controller.setAudioVolume(volumePercent);
        }

        XboxWirelessController[] updated = new XboxWirelessController[targets.length + 1];
        System.arraycopy(targets, 0, updated, 0, targets.length);
        updated[targets.length] = controller;
        targets = updated;

        return true;
    }

    /**
     * Removes a pad and tells it to stop. Safe for a pad that was never enabled, which is what
     * makes this usable straight from the disconnect path.
     */
    public synchronized void disable(XboxWirelessController controller) {
        if (!isEnabled(controller)) {
            return;
        }

        XboxWirelessController[] updated = new XboxWirelessController[targets.length - 1];
        int i = 0;
        for (XboxWirelessController target : targets) {
            if (target != controller) {
                updated[i++] = target;
            }
        }
        targets = updated;

        // After the list, so a frame racing us sends to a pad we are stopping rather than to one
        // that has already been told to stop
        controller.setAudioEnabled(false);
    }

    /** Stops every pad. Called when the stream ends. */
    public synchronized void disableAll() {
        XboxWirelessController[] previous = targets;
        targets = NONE;

        for (XboxWirelessController target : previous) {
            target.setAudioEnabled(false);
        }
    }
}
