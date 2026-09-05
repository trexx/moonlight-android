package com.limelight.binding.input.driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Switch Pro Controller stick calibration.
 *
 * <p>These cover the three things HARDWARE_TESTING.md lists as the symptoms of bad calibration -
 * a dead zone that is too large, a range that clips early, and a drifting centre - none of which
 * fail loudly on a device. The 12-bit nibble packing and the differing field order between the two
 * sticks are the parts most likely to be got wrong.
 */
class StickCalibrationTest {

    private static final int PACKET_SIZE = 64;
    private static final int PAYLOAD_OFFSET = 20;

    /**
     * Packs three 12-bit pairs into the 9-byte flash record layout, matching what the controller
     * returns from an SPI read. Each pair occupies 3 bytes: the first value in byte 0 plus the low
     * nibble of byte 1, the second in the high nibble of byte 1 plus byte 2.
     */
    private static byte[] flashRecord(int a1, int b1, int a2, int b2, int a3, int b3) {
        byte[] buffer = new byte[PACKET_SIZE];
        int[] values = {a1, b1, a2, b2, a3, b3};
        for (int pair = 0; pair < 3; pair++) {
            int a = values[pair * 2];
            int b = values[pair * 2 + 1];
            int at = PAYLOAD_OFFSET + pair * 3;
            buffer[at] = (byte) (a & 0xFF);
            buffer[at + 1] = (byte) (((a >> 8) & 0x0F) | ((b & 0x0F) << 4));
            buffer[at + 2] = (byte) ((b >> 4) & 0xFF);
        }
        return buffer;
    }

    @Nested
    @DisplayName("flash record unpacking")
    class Unpacking {

        @Test
        @DisplayName("round-trips 12-bit values through the nibble packing")
        void roundTripsTwelveBitValues() {
            // Values chosen so a dropped or swapped nibble cannot coincidentally still match
            int xMax = 0x5A3, yMax = 0x6C7, xCenter = 0x7FE, yCenter = 0x812, xMin = 0x59D, yMin = 0x6B1;
            byte[] buffer = flashRecord(xMax, yMax, xCenter, yCenter, xMin, yMin);

            StickCalibration calibration = new StickCalibration();
            calibration.loadLeftStickFlash(buffer);

            // X: {center - min, center, center + max}
            assertEquals(xCenter - xMin, calibration.calibration(0, 0, 0));
            assertEquals(xCenter, calibration.calibration(0, 0, 1));
            assertEquals(xCenter + xMax, calibration.calibration(0, 0, 2));
            // Y is stored inverted, hence the subtractions from 0x1000
            assertEquals(0x1000 - yCenter - yMax, calibration.calibration(0, 1, 0));
            assertEquals(0x1000 - yCenter, calibration.calibration(0, 1, 1));
            assertEquals(0x1000 - yCenter + yMin, calibration.calibration(0, 1, 2));
        }

        @Test
        @DisplayName("handles the full 12-bit range at both ends")
        void handlesFullTwelveBitRange() {
            byte[] buffer = flashRecord(0x000, 0xFFF, 0xFFF, 0x000, 0x001, 0xFFE);

            StickCalibration calibration = new StickCalibration();
            calibration.loadLeftStickFlash(buffer);

            // xMax=0x000, yMax=0xFFF, xCenter=0xFFF, yCenter=0x000, xMin=0x001, yMin=0xFFE
            assertEquals(0xFFF - 0x001, calibration.calibration(0, 0, 0));
            assertEquals(0xFFF, calibration.calibration(0, 0, 1));
            assertEquals(0xFFF + 0x000, calibration.calibration(0, 0, 2));
            assertEquals(0x1000 - 0x000 - 0xFFF, calibration.calibration(0, 1, 0));
        }

        @Test
        @DisplayName("ignores bytes outside the 9-byte payload window")
        void ignoresBytesOutsidePayload() {
            byte[] buffer = flashRecord(0x5A3, 0x6C7, 0x7FE, 0x812, 0x59D, 0x6B1);
            byte[] noisy = buffer.clone();
            // Scribble over everything except bytes 20..28
            for (int i = 0; i < noisy.length; i++) {
                if (i < PAYLOAD_OFFSET || i > PAYLOAD_OFFSET + 8) {
                    noisy[i] = (byte) 0xA5;
                }
            }

            StickCalibration fromClean = new StickCalibration();
            fromClean.loadLeftStickFlash(buffer);
            StickCalibration fromNoisy = new StickCalibration();
            fromNoisy.loadLeftStickFlash(noisy);

            for (int axis = 0; axis < 2; axis++) {
                for (int index = 0; index < 3; index++) {
                    assertEquals(fromClean.calibration(0, axis, index),
                            fromNoisy.calibration(0, axis, index));
                }
            }
        }
    }

    @Nested
    @DisplayName("stick field ordering")
    class FieldOrdering {

        @Test
        @DisplayName("reads the left stick as max, center, min")
        void leftStickIsMaxCenterMin() {
            byte[] buffer = flashRecord(0x100, 0x200, 0x800, 0x900, 0x300, 0x400);

            StickCalibration calibration = new StickCalibration();
            calibration.loadLeftStickFlash(buffer);

            // xMax=0x100, xCenter=0x800, xMin=0x300
            assertEquals(0x800, calibration.calibration(0, 0, 1));
            assertEquals(0x800 - 0x300, calibration.calibration(0, 0, 0));
            assertEquals(0x800 + 0x100, calibration.calibration(0, 0, 2));
        }

        @Test
        @DisplayName("reads the right stick as center, min, max")
        void rightStickIsCenterMinMax() {
            byte[] buffer = flashRecord(0x100, 0x200, 0x800, 0x900, 0x300, 0x400);

            StickCalibration calibration = new StickCalibration();
            calibration.loadRightStickFlash(buffer);

            // Same bytes, different meaning: xCenter=0x100, xMin=0x800, xMax=0x300
            assertEquals(0x100, calibration.calibration(1, 0, 1));
            assertEquals(0x100 - 0x800, calibration.calibration(1, 0, 0));
            assertEquals(0x100 + 0x300, calibration.calibration(1, 0, 2));
        }

        @Test
        @DisplayName("does not read both sticks with the same field order")
        void sticksUseDifferentOrders() {
            // The regression guard: if loadRightStickFlash were ever made to share the left
            // stick's unpacking, identical bytes would produce identical calibration.
            byte[] buffer = flashRecord(0x100, 0x200, 0x800, 0x900, 0x300, 0x400);

            StickCalibration left = new StickCalibration();
            left.loadLeftStickFlash(buffer);
            StickCalibration right = new StickCalibration();
            right.loadRightStickFlash(buffer);

            assertTrue(left.calibration(0, 0, 1) != right.calibration(1, 0, 1),
                    "left and right sticks must not unpack the centre from the same position");
        }

        @Test
        @DisplayName("keeps the two sticks' state independent")
        void sticksAreIndependent() {
            StickCalibration calibration = new StickCalibration();
            calibration.loadLeftStickFlash(flashRecord(0x100, 0x100, 0x800, 0x800, 0x100, 0x100));
            calibration.applyDefaultCalibration(1);

            assertEquals(0x800, calibration.calibration(0, 0, 1));
            assertEquals(0x800, calibration.calibration(1, 0, 1));
            assertEquals(0x800 - 0x100, calibration.calibration(0, 0, 0));
            assertEquals(0x000, calibration.calibration(1, 0, 0));
        }
    }

    @Nested
    @DisplayName("applyDefaultCalibration()")
    class DefaultCalibration {

        @Test
        @DisplayName("uses nominal full-scale 12-bit bounds")
        void usesNominalFullScaleBounds() {
            StickCalibration calibration = new StickCalibration();
            calibration.applyDefaultCalibration(0);

            for (int axis = 0; axis < 2; axis++) {
                assertEquals(0x000, calibration.calibration(0, axis, 0));
                assertEquals(0x800, calibration.calibration(0, axis, 1));
                assertEquals(0xFFF, calibration.calibration(0, axis, 2));
                assertEquals(-0x700, calibration.extent(0, axis, 0), 0.001f);
                assertEquals(0x700, calibration.extent(0, axis, 1), 0.001f);
            }
        }

        @Test
        @DisplayName("centres at rest, so an unreadable flash still leaves a usable stick")
        void restReadsAsCentred() {
            // The driver falls back to this when the SPI read fails rather than failing init.
            StickCalibration calibration = new StickCalibration();
            calibration.applyDefaultCalibration(0);

            assertEquals(0.0f, calibration.leftX.apply(0x800), 0.001f);
        }
    }

    @Nested
    @DisplayName("apply()")
    class Apply {

        private StickCalibration centred() {
            StickCalibration calibration = new StickCalibration();
            // A stick centred at exactly mid-scale with symmetric +/- 0x400 travel
            calibration.applyCalibration(0, 0x400, 0x800, 0x400, 0x400, 0x800, 0x400);
            return calibration;
        }

        @Test
        @DisplayName("reports zero at the calibrated centre")
        void reportsZeroAtCentre() {
            assertEquals(0.0f, centred().leftX.apply(0x800), 0.001f);
        }

        @Test
        @DisplayName("scales partial deflection proportionally within the usable extent")
        void scalesPartialDeflection() {
            StickCalibration calibration = centred();

            // Usable extent starts at 70% of 0x400 = 716.8; half of that should read ~0.5
            float half = calibration.leftX.apply(0x800 + 358);
            assertEquals(0.5f, half, 0.01f);
        }

        @Test
        @DisplayName("reports full deflection in both directions")
        void reportsFullDeflectionBothWays() {
            StickCalibration calibration = centred();

            assertEquals(1.0f, calibration.leftX.apply(0xFFF), 0.001f);
            assertEquals(-1.0f, calibration.leftX.apply(0x000), 0.001f);
        }

        @Test
        @DisplayName("widens the extent when a reading exceeds it, and does not narrow back")
        void widensExtentButNeverNarrows() {
            StickCalibration calibration = centred();
            float extentBefore = calibration.extent(0, 0, 1);

            // A deflection past the calibrated extent becomes the new extent
            assertEquals(1.0f, calibration.leftX.apply(0x800 + 900), 0.001f);
            assertEquals(900, calibration.extent(0, 0, 1), 0.001f);
            assertTrue(calibration.extent(0, 0, 1) > extentBefore);

            // The same input now reads as exactly full scale rather than widening again
            assertEquals(1.0f, calibration.leftX.apply(0x800 + 900), 0.001f);
            assertEquals(900, calibration.extent(0, 0, 1), 0.001f);

            // A smaller deflection scales against the widened extent and leaves it alone
            assertEquals(0.5f, calibration.leftX.apply(0x800 + 450), 0.001f);
            assertEquals(900, calibration.extent(0, 0, 1), 0.001f);
        }

        @Test
        @DisplayName("wraps negative readings back into the unsigned 12-bit range")
        void wrapsNegativeReadings() {
            // handleRead() negates the Y axes before calling in, so apply() has to undo that.
            StickCalibration calibration = centred();

            // -0x800 wraps to 0x800, the centre
            assertEquals(0.0f, calibration.leftY.apply(-0x800), 0.001f);
        }

        @Test
        @DisplayName("keeps axes and sticks from sharing extents")
        void keepsAxesIndependent() {
            StickCalibration calibration = new StickCalibration();
            calibration.applyDefaultCalibration(0);
            calibration.applyDefaultCalibration(1);

            // Widening left X must not touch left Y or either right axis
            calibration.leftX.apply(0x800 + 2000);

            assertEquals(2000, calibration.extent(0, 0, 1), 0.001f);
            assertEquals(0x700, calibration.extent(0, 1, 1), 0.001f);
            assertEquals(0x700, calibration.extent(1, 0, 1), 0.001f);
            assertEquals(0x700, calibration.extent(1, 1, 1), 0.001f);
        }

        @Test
        @DisplayName("never returns NaN for a record with no travel in it")
        void degenerateRecordDoesNotProduceNaN() {
            // Erased flash, a partial SPI read or a clone pad can report a record whose deflection
            // deltas are all zero. Without the floor in applyCalibration the extents come out at
            // exactly 0.0f, every comparison in apply() is strict so none of them catches it, and
            // a reading at the centre evaluates 0.0f / 0.0f. NaN then rides the axis for the whole
            // session, because the extents only widen.
            StickCalibration calibration = new StickCalibration();
            calibration.applyCalibration(0, 0, 0x800, 0, 0, 0x800, 0);

            for (float reading : new float[] {
                    calibration.leftX.apply(0x800),
                    calibration.leftY.apply(-0x800),
                    calibration.leftX.apply(0x801),
                    calibration.leftX.apply(0x7FF)}) {
                assertFalse(Float.isNaN(reading), "axis read NaN from a zero-travel record");
                assertTrue(reading >= -1.0f && reading <= 1.0f,
                        "axis escaped the -1..1 range: " + reading);
            }
        }

        @Test
        @DisplayName("still reports zero at the centre of a zero-travel record")
        void degenerateRecordStillCentres() {
            StickCalibration calibration = new StickCalibration();
            calibration.applyCalibration(0, 0, 0x800, 0, 0, 0x800, 0);

            assertEquals(0.0f, calibration.leftX.apply(0x800), 0.001f);
        }
    }

    @Nested
    @DisplayName("extent derivation")
    class ExtentDerivation {

        @Test
        @DisplayName("starts X at 70% of the calibrated travel")
        void startsXAtSeventyPercent() {
            StickCalibration calibration = new StickCalibration();
            calibration.applyCalibration(0, 0x400, 0x800, 0x500, 0x400, 0x800, 0x500);

            assertEquals(0x400 * -0.7f, calibration.extent(0, 0, 0), 0.01f);
            assertEquals(0x500 * 0.7f, calibration.extent(0, 0, 1), 0.01f);
        }

        @Test
        @DisplayName("mirrors Y against X for a stick centred at mid-scale")
        void mirrorsYForMidScaleCentre() {
            // Y is stored inverted, so its extents should be X's with min and max exchanged:
            // the max travel becomes the negative extent. With yCenter at exactly 0x800 that is
            // what happens.
            StickCalibration calibration = new StickCalibration();
            calibration.applyCalibration(0, 0x400, 0x800, 0x500, 0x400, 0x800, 0x500);

            assertEquals(0x400 * -0.7f, calibration.extent(0, 0, 0), 0.01f);
            assertEquals(0x500 * 0.7f, calibration.extent(0, 0, 1), 0.01f);
            assertEquals(0x500 * -0.7f, calibration.extent(0, 1, 0), 0.01f);
            assertEquals(0x400 * 0.7f, calibration.extent(0, 1, 1), 0.01f);
        }

        @Test
        @DisplayName("skews the Y extents when the centre is not at mid-scale")
        void yExtentsSkewOffMidScale() {
            // Documents current behaviour rather than endorsing it. The X extents are derived from
            // the calibrated bound (stickCalibration[stick][0][1]), but the Y extents use the raw
            // yCenter where the calibrated bound is 0x1000 - yCenter. The two agree only when
            // yCenter is exactly 0x800. Otherwise both Y extents shift by the same signed amount,
            // 2 * (yCenter - 0x800) * -0.7, which slides the usable window off centre rather than
            // resizing it - the "drifting centre" symptom in HARDWARE_TESTING.md. Fixing it
            // changes stick feel, so it needs a controller to verify.
            int yMin = 0x400, yCenter = 0x900, yMax = 0x500;
            StickCalibration calibration = new StickCalibration();
            calibration.applyCalibration(0, 0x400, 0x800, 0x500, yMin, yCenter, yMax);

            float skew = 2 * (yCenter - 0x800) * -0.7f;

            assertEquals(yMax * -0.7f + skew, calibration.extent(0, 1, 0), 0.01f);
            assertEquals(yMin * 0.7f + skew, calibration.extent(0, 1, 1), 0.01f);
        }
    }
}
