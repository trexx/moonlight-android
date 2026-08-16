package com.limelight.binding.input.driver;

/**
 * Stick calibration for a Switch Pro Controller: the bounds read out of the controller's SPI
 * flash, and the normalisation of raw readings against them.
 *
 * <p>Split out of {@link ProConController} so it can be exercised without a controller plugged in.
 * Bad calibration does not fail loudly - it shows up as a dead zone that is too large, a range
 * that clips early, or a centre that drifts - so the arithmetic is worth pinning down off-device.
 *
 * <p>Holds no USB state: {@link ProConController} does the flash reads and hands the resulting
 * 9-byte records here.
 */
final class StickCalibration {

    /** Offset of the calibration payload within an SPI flash reply. */
    private static final int FLASH_PAYLOAD_OFFSET = 20;

    /** [stick][axis][min, center, max], stick 0 left and 1 right, axis 0 X and 1 Y. */
    private final int[][][] stickCalibration = new int[2][2][3];

    /** Pre-calculated usable extent either side of centre, [stick][axis][negative, positive]. */
    private final float[][][] stickExtends = new float[2][2][2];

    /**
     * Unpacks the left stick's record. The left stick stores max first, then center, then min.
     */
    void loadLeftStickFlash(byte[] buffer) {
        int xMax = unpackLow(buffer, 0);
        int yMax = unpackHigh(buffer, 0);
        int xCenter = unpackLow(buffer, 3);
        int yCenter = unpackHigh(buffer, 3);
        int xMin = unpackLow(buffer, 6);
        int yMin = unpackHigh(buffer, 6);
        applyCalibration(0, xMin, xCenter, xMax, yMin, yCenter, yMax);
    }

    /**
     * Unpacks the right stick's record. The right stick stores center first, then min, then max -
     * a different order to the left, which is the detail most easily got wrong here.
     */
    void loadRightStickFlash(byte[] buffer) {
        int xCenter = unpackLow(buffer, 0);
        int yCenter = unpackHigh(buffer, 0);
        int xMin = unpackLow(buffer, 3);
        int yMin = unpackHigh(buffer, 3);
        int xMax = unpackLow(buffer, 6);
        int yMax = unpackHigh(buffer, 6);
        applyCalibration(1, xMin, xCenter, xMax, yMin, yCenter, yMax);
    }

    /** Reads the first 12-bit value of a packed pair: all of byte 0 plus the low nibble of byte 1. */
    private static int unpackLow(byte[] buffer, int index) {
        int at = FLASH_PAYLOAD_OFFSET + index;
        return (buffer[at] & 0xFF) | ((buffer[at + 1] & 0x0F) << 8);
    }

    /** Reads the second 12-bit value of a packed pair: high nibble of byte 1 plus all of byte 2. */
    private static int unpackHigh(byte[] buffer, int index) {
        int at = FLASH_PAYLOAD_OFFSET + index;
        return ((buffer[at + 1] & 0xF0) >> 4) | ((buffer[at + 2] & 0xFF) << 4);
    }

    /**
     * Converts one stick's raw calibration triple into absolute bounds and usable extents.
     *
     * <p>The flash values are deltas from centre rather than absolute positions, and the Y axis
     * is stored inverted relative to the direction {@code ProConController.handleRead} produces,
     * hence the subtractions from 0x1000.
     */
    void applyCalibration(int stick, int xMin, int xCenter, int xMax, int yMin, int yCenter, int yMax) {
        stickCalibration[stick][0][0] = xCenter - xMin;
        stickCalibration[stick][0][1] = xCenter;
        stickCalibration[stick][0][2] = xCenter + xMax;
        stickCalibration[stick][1][0] = 0x1000 - yCenter - yMax;
        stickCalibration[stick][1][1] = 0x1000 - yCenter;
        stickCalibration[stick][1][2] = 0x1000 - yCenter + yMin;

        // Start the usable range at 70% of the calibrated extent. apply() widens these if it ever
        // sees a larger deflection, so a conservative start just means full range is reached after
        // the stick has been pushed to its corners once.
        stickExtends[stick][0][0] = (float) ((xCenter - stickCalibration[stick][0][0]) * -0.7);
        stickExtends[stick][0][1] = (float) ((stickCalibration[stick][0][2] - xCenter) * 0.7);
        stickExtends[stick][1][0] = (float) ((yCenter - stickCalibration[stick][1][0]) * -0.7);
        stickExtends[stick][1][1] = (float) ((stickCalibration[stick][1][2] - yCenter) * 0.7);
    }

    /** Nominal full-scale 12-bit calibration, used when flash can't be read. */
    void applyDefaultCalibration(int stick) {
        for (int axis = 0; axis < 2; axis++) {
            stickCalibration[stick][axis][0] = 0x000;  // Min
            stickCalibration[stick][axis][1] = 0x800;  // Center
            stickCalibration[stick][axis][2] = 0xFFF;  // Max

            stickExtends[stick][axis][0] = -0x700;
            stickExtends[stick][axis][1] = 0x700;
        }
    }

    /**
     * Normalises a raw axis reading to -1.0 to 1.0 using the loaded calibration.
     *
     * <p>Self-widening: a reading beyond the current extent becomes the new extent and reports
     * full deflection. That absorbs sticks whose real range differs from what flash claims, at
     * the cost of the range only being fully learned once the stick has been pushed to its
     * corners. It also means the extents are per-session and never shrink back.
     *
     * @param stick 0 left, 1 right
     * @param axis  0 X, 1 Y
     */
    float apply(int value, int stick, int axis) {
        int center = stickCalibration[stick][axis][1];

        // ProConController.handleRead() negates the Y axes, so wrap them back into the unsigned
        // 12-bit range
        if (value < 0) {
            value += 0x1000;
        }

        value -= center;

        if (value < stickExtends[stick][axis][0]) {
            stickExtends[stick][axis][0] = value;
            return -1;
        } else if (value > stickExtends[stick][axis][1]) {
            stickExtends[stick][axis][1] = value;
            return 1;
        }

        if (value > 0) {
            return value / stickExtends[stick][axis][1];
        } else {
            return -value / stickExtends[stick][axis][0];
        }
    }

    // Accessors for tests. Nothing in the driver reads these back.

    int calibration(int stick, int axis, int index) {
        return stickCalibration[stick][axis][index];
    }

    float extent(int stick, int axis, int index) {
        return stickExtends[stick][axis][index];
    }
}
