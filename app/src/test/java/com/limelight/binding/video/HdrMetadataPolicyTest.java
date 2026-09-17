package com.limelight.binding.video;

import com.limelight.binding.video.HdrMetadataPolicy.Action;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the HDR restart decision.
 *
 * <p>A wrong answer here is either a codec restart that drops frames for nothing, or a TV left
 * in SDR because the key was withheld from a decoder that needed it. Neither is visible on a
 * JVM, so every path through the two events is pinned.
 */
class HdrMetadataPolicyTest {

    private static final byte[] META_A = {1, 2, 3};
    private static final byte[] META_B = {4, 5, 6};

    @Test
    @DisplayName("announced before the format: defers, then no restart on a decoder that publishes")
    void deferThenInBand() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        assertEquals(Action.DEFER, p.onHdrMode(true, META_A));
        assertEquals("pending", p.describe());
        assertEquals(Action.NONE, p.onOutputFormat(true, false));
        assertNull(p.metadataToApply());
        assertEquals("in-band", p.describe());
    }

    @Test
    @DisplayName("announced before the format: defers, then restarts with the key on a decoder that does not")
    void deferThenViaKey() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        assertEquals(Action.DEFER, p.onHdrMode(true, META_A));
        assertEquals(Action.RESTART, p.onOutputFormat(false, false));
        assertArrayEquals(META_A, p.metadataToApply());
        assertEquals("via key", p.describe());
    }

    @Test
    @DisplayName("format seen first without static info: an announcement restarts at once")
    void formatWithoutThenAnnounce() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        assertEquals(Action.NONE, p.onOutputFormat(false, false));
        assertEquals(Action.RESTART, p.onHdrMode(true, META_A));
        assertArrayEquals(META_A, p.metadataToApply());
    }

    @Test
    @DisplayName("format seen first with static info: an announcement changes nothing")
    void formatWithThenAnnounce() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        assertEquals(Action.NONE, p.onOutputFormat(true, false));
        assertEquals(Action.NONE, p.onHdrMode(true, META_A));
        assertNull(p.metadataToApply());
        assertEquals("in-band", p.describe());
    }

    @Test
    @DisplayName("re-announcing identical metadata is a no-op on either kind of decoder")
    void identicalReannouncementIsNoOp() {
        HdrMetadataPolicy viaKey = new HdrMetadataPolicy();
        viaKey.onOutputFormat(false, false);
        viaKey.onHdrMode(true, META_A);
        assertEquals(Action.NONE, viaKey.onHdrMode(true, META_A.clone()));

        HdrMetadataPolicy inBand = new HdrMetadataPolicy();
        inBand.onOutputFormat(true, false);
        inBand.onHdrMode(true, META_A);
        assertEquals(Action.NONE, inBand.onHdrMode(true, META_A.clone()));
    }

    @Test
    @DisplayName("a metadata change and HDR off restart a via-key decoder and not an in-band one")
    void changesAfterTheFirstAnnouncement() {
        HdrMetadataPolicy viaKey = new HdrMetadataPolicy();
        viaKey.onOutputFormat(false, false);
        viaKey.onHdrMode(true, META_A);
        assertEquals(Action.RESTART, viaKey.onHdrMode(true, META_B));
        assertArrayEquals(META_B, viaKey.metadataToApply());
        assertEquals(Action.RESTART, viaKey.onHdrMode(false, null));
        assertNull(viaKey.metadataToApply());

        HdrMetadataPolicy inBand = new HdrMetadataPolicy();
        inBand.onOutputFormat(true, false);
        inBand.onHdrMode(true, META_A);
        assertEquals(Action.NONE, inBand.onHdrMode(true, META_B));
        assertEquals(Action.NONE, inBand.onHdrMode(false, null));
        assertNull(inBand.metadataToApply());
    }

    @Test
    @DisplayName("HDR off when nothing was ever announced is a no-op")
    void offWithoutAnnouncement() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        assertEquals(Action.NONE, p.onHdrMode(false, null));
        assertEquals(Action.NONE, p.onHdrMode(true, null));
        assertEquals("never announced", p.describe());
        p.onOutputFormat(false, false);
        assertEquals(Action.NONE, p.onHdrMode(false, null));
    }

    @Test
    @DisplayName("static info that only echoes the configured key is not evidence of parsing")
    void echoedKeyIsNotEvidence() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        p.onOutputFormat(false, false);
        assertEquals(Action.RESTART, p.onHdrMode(true, META_A));
        // The restarted codec, configured with the key, reports the key back
        assertEquals(Action.NONE, p.onOutputFormat(true, true));
        assertEquals("via key", p.describe());
        // ...so the next change still restarts
        assertEquals(Action.RESTART, p.onHdrMode(true, META_B));
        assertArrayEquals(META_B, p.metadataToApply());
    }

    @Test
    @DisplayName("static info seen once stays known across a later format without it")
    void publishingIsSticky() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        p.onOutputFormat(true, false);
        p.onOutputFormat(false, false);
        assertEquals(Action.NONE, p.onHdrMode(true, META_A));
        assertNull(p.metadataToApply());
    }

    @Test
    @DisplayName("a second announcement while deferred replaces the first")
    void latestAnnouncementWinsWhileDeferred() {
        HdrMetadataPolicy p = new HdrMetadataPolicy();
        assertEquals(Action.DEFER, p.onHdrMode(true, META_A));
        assertEquals(Action.DEFER, p.onHdrMode(true, META_B));
        assertEquals(Action.RESTART, p.onOutputFormat(false, false));
        assertArrayEquals(META_B, p.metadataToApply());
    }
}
