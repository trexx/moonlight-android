package com.limelight.binding.video;

import com.limelight.binding.video.LowLatencyOptions.Family;
import com.limelight.binding.video.LowLatencyOptions.Option;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.limelight.binding.video.LowLatencyOptions.KEY_AMLOGIC_LOW_LATENCY;
import static com.limelight.binding.video.LowLatencyOptions.KEY_LOW_LATENCY;
import static com.limelight.binding.video.LowLatencyOptions.KEY_OPERATING_RATE;
import static com.limelight.binding.video.LowLatencyOptions.KEY_PRIORITY;
import static com.limelight.binding.video.LowLatencyOptions.KEY_QTI_LOW_LATENCY;
import static com.limelight.binding.video.LowLatencyOptions.KEY_QTI_PICTURE_ORDER;
import static com.limelight.binding.video.LowLatencyOptions.KEY_VDEC_LOW_LATENCY;
import static com.limelight.binding.video.LowLatencyOptions.MAX_OPERATING_RATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the low-latency option ladder.
 *
 * <p>The ladder is only ever exercised on a device, one rung per failed configure, so a rung that
 * is wrong shows up as a decoder that silently runs at higher latency than it could. The cases
 * here pin each rung's contents, and in particular that a decoder advertising
 * {@code FEATURE_LowLatency} now gets its vendor key on the first attempt.
 */
class LowLatencyOptionsTest {

    private static final int FPS = 60;

    private static boolean has(List<Option> options, String key, int value) {
        return options.contains(new Option(key, value));
    }

    private static boolean hasKey(List<Option> options, String key) {
        for (Option o : options) {
            if (o.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a feature-advertising decoder gets the vendor key and priority on rung 0")
    void featureDecoderRung0CarriesVendorKeyAndPriority() {
        List<Option> rung0 = LowLatencyOptions.forTry(0, Family.AMLOGIC, true, FPS);

        assertTrue(has(rung0, KEY_LOW_LATENCY, 1));
        assertTrue(has(rung0, KEY_AMLOGIC_LOW_LATENCY, 1));
        assertTrue(has(rung0, KEY_PRIORITY, 0));
        assertTrue(has(rung0, KEY_OPERATING_RATE, FPS));
        // vdec-lowlatency breaks the MITV4, so it stays on its own rung as before
        assertFalse(hasKey(rung0, KEY_VDEC_LOW_LATENCY));
    }

    @Test
    @DisplayName("a feature-advertising decoder falls back to the official key alone on rung 1")
    void featureDecoderRung1IsOfficialKeyAlone() {
        assertEquals(List.of(new Option(KEY_LOW_LATENCY, 1)),
                LowLatencyOptions.forTry(1, Family.AMLOGIC, true, FPS));
    }

    @Test
    @DisplayName("past rung 1, a feature-advertising decoder walks the plain ladder from its rung 1")
    void featureDecoderThenFollowsPlainLadderShiftedByOne() {
        for (int rung = 1; rung <= 5; rung++) {
            assertEquals(LowLatencyOptions.forTry(rung, Family.AMLOGIC, false, FPS),
                    LowLatencyOptions.forTry(rung + 1, Family.AMLOGIC, true, FPS),
                    "rung " + rung);
        }
    }

    @Test
    @DisplayName("without the feature, rung 0 is the full set and each rung drops one option")
    void plainLadderDropsOneOptionPerRung() {
        List<Option> rung0 = LowLatencyOptions.forTry(0, Family.AMLOGIC, false, FPS);
        assertEquals(List.of(
                new Option(KEY_LOW_LATENCY, 1),
                new Option(KEY_VDEC_LOW_LATENCY, 1),
                new Option(KEY_PRIORITY, 0),
                new Option(KEY_OPERATING_RATE, FPS),
                new Option(KEY_AMLOGIC_LOW_LATENCY, 1)), rung0);

        assertFalse(hasKey(LowLatencyOptions.forTry(1, Family.AMLOGIC, false, FPS), KEY_LOW_LATENCY));
        assertFalse(hasKey(LowLatencyOptions.forTry(2, Family.AMLOGIC, false, FPS), KEY_VDEC_LOW_LATENCY));
        assertEquals(List.of(new Option(KEY_AMLOGIC_LOW_LATENCY, 1)),
                LowLatencyOptions.forTry(3, Family.AMLOGIC, false, FPS));
        assertTrue(LowLatencyOptions.forTry(4, Family.AMLOGIC, false, FPS).isEmpty(), "exhausted");
    }

    @Test
    @DisplayName("the operating rate follows the stream rate and travels with priority")
    void operatingRateFollowsTheStreamRate() {
        List<Option> at120 = LowLatencyOptions.forTry(0, Family.OTHER, false, 120);
        assertTrue(has(at120, KEY_OPERATING_RATE, 120));
        assertTrue(has(at120, KEY_PRIORITY, 0));

        // Dropped on the same rung as priority, never on its own
        List<Option> rung3 = LowLatencyOptions.forTry(3, Family.AMLOGIC, false, 120);
        assertFalse(hasKey(rung3, KEY_OPERATING_RATE));
        assertFalse(hasKey(rung3, KEY_PRIORITY));
    }

    @Test
    @DisplayName("Qualcomm gets the maximum operating rate instead of priority, and a fifth rung")
    void qualcommUsesOperatingRateAndHasAnExtraRung() {
        List<Option> rung0 = LowLatencyOptions.forTry(0, Family.QUALCOMM, false, FPS);
        assertTrue(has(rung0, KEY_OPERATING_RATE, MAX_OPERATING_RATE));
        assertFalse(has(rung0, KEY_OPERATING_RATE, FPS));
        assertFalse(hasKey(rung0, KEY_PRIORITY));
        assertTrue(has(rung0, KEY_QTI_PICTURE_ORDER, 1));
        assertTrue(has(rung0, KEY_QTI_LOW_LATENCY, 1));

        assertEquals(List.of(new Option(KEY_QTI_LOW_LATENCY, 1)),
                LowLatencyOptions.forTry(4, Family.QUALCOMM, false, FPS));
        assertTrue(LowLatencyOptions.forTry(5, Family.QUALCOMM, false, FPS).isEmpty());
    }

    @Test
    @DisplayName("a family with no vendor key exhausts after the generic options")
    void otherFamilyHasNoVendorRung() {
        assertEquals(List.of(new Option(KEY_PRIORITY, 0), new Option(KEY_OPERATING_RATE, FPS)),
                LowLatencyOptions.forTry(2, Family.OTHER, false, FPS));
        assertTrue(LowLatencyOptions.forTry(3, Family.OTHER, false, FPS).isEmpty());
        // With the feature: official key + priority on rung 0, official key on rung 1, then nothing
        assertEquals(List.of(new Option(KEY_LOW_LATENCY, 1), new Option(KEY_PRIORITY, 0),
                        new Option(KEY_OPERATING_RATE, FPS)),
                LowLatencyOptions.forTry(0, Family.OTHER, true, FPS));
        assertTrue(LowLatencyOptions.forTry(4, Family.OTHER, true, FPS).isEmpty());
    }
}
