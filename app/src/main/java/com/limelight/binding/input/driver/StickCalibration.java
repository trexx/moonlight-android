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
 *
 * <p>The four axes are separate objects with scalar fields rather than slots in
 * {@code int[2][2][3]} and {@code float[2][2][2]} arrays, which is how this was originally
 * written. Jagged arrays in Java are not flat blocks: those two declarations are fourteen heap
 * objects, each leaf carrying a header larger than its payload and sitting wherever the allocator
 * put it, so every read walked three dependent pointers and paid three bounds checks. The driver
 * only ever asks for the four combinations below, and asks with constant indices, so the
 * navigation bought nothing. {@link Axis#apply} now touches three fields of one object.
 */
final class StickCalibration {

    /** Offset of the calibration payload within an SPI flash reply. */
    private static final int FLASH_PAYLOAD_OFFSET = 20;

    // Never reassigned, so ProConController is free to hold these references directly rather than
    // reaching through this object on every read.
    final Axis leftX = new Axis();
    final Axis leftY = new Axis();
    final Axis rightX = new Axis();
    final Axis rightY = new Axis();

    /**
     * One axis: its calibrated bounds, and the running extents {@link #apply} widens.
     *
     * <p>{@code min}, {@code center} and {@code max} are absolute positions in the raw 12-bit
     * range, already converted from the deltas flash stores.
     */
    static final class Axis {

        private int min;
        private int center;
        private int max;

        /** Usable extent below centre. Negative. */
        private float negExtent;
        /** Usable extent above centre. Positive. */
        private float posExtent;

        /**
         * Normalises a raw axis reading to -1.0 to 1.0 using the loaded calibration.
         *
         * <p>Self-widening: a reading beyond the current extent becomes the new extent and reports
         * full deflection. That absorbs sticks whose real range differs from what flash claims, at
         * the cost of the range only being fully learned once the stick has been pushed to its
         * corners. It also means the extents are per-session and never shrink back.
         *
         * <p>Called four times per {@code ProConController.handleRead()}, which turns over at
         * roughly 120 Hz per controller. It allocates nothing and reads only fields of this object.
         */
        float apply(int value) {
            // ProConController.handleRead() negates the Y axes, so wrap them back into the unsigned
            // 12-bit range
            if (value < 0) {
                value += 0x1000;
            }

            value -= center;

            if (value < negExtent) {
                negExtent = value;
                return -1;
            } else if (value > posExtent) {
                posExtent = value;
                return 1;
            }

            if (value > 0) {
                return value / posExtent;
            } else {
                return -value / negExtent;
            }
        }

        /** Nominal full-scale 12-bit calibration, used when flash can't be read. */
        private void applyDefaults() {
            min = 0x000;
            center = 0x800;
            max = 0xFFF;

            negExtent = -0x700;
            posExtent = 0x700;
        }
    }

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
        Axis x = stick == 0 ? leftX : rightX;
        Axis y = stick == 0 ? leftY : rightY;

        x.min = xCenter - xMin;
        x.center = xCenter;
        x.max = xCenter + xMax;
        y.min = 0x1000 - yCenter - yMax;
        y.center = 0x1000 - yCenter;
        y.max = 0x1000 - yCenter + yMin;

        // Start the usable range at 70% of the calibrated extent. apply() widens these if it ever
        // sees a larger deflection, so a conservative start just means full range is reached after
        // the stick has been pushed to its corners once.
        //
        // Note the asymmetry, carried over verbatim from the driver this came from: the X extents
        // are measured from x.center, but the Y extents are measured from the raw yCenter, which is
        // not y.center - that was inverted to 0x1000 - yCenter two lines above. The two agree only
        // for a stick whose flash centre is exactly 0x800. See the Y-axis note in
        // HARDWARE_TESTING.md; it needs a controller to settle, so it is recorded rather than
        // changed here.
        x.negExtent = (float) ((xCenter - x.min) * -0.7);
        x.posExtent = (float) ((x.max - xCenter) * 0.7);
        y.negExtent = (float) ((yCenter - y.min) * -0.7);
        y.posExtent = (float) ((y.max - yCenter) * 0.7);
    }

    /** Nominal full-scale 12-bit calibration, used when flash can't be read. */
    void applyDefaultCalibration(int stick) {
        if (stick == 0) {
            leftX.applyDefaults();
            leftY.applyDefaults();
        } else {
            rightX.applyDefaults();
            rightY.applyDefaults();
        }
    }

    // Accessors for tests. Nothing in the driver reads these back - it holds the Axis objects
    // directly - so the index navigation here is off the hot path entirely.

    private Axis axisFor(int stick, int axis) {
        if (stick == 0) {
            return axis == 0 ? leftX : leftY;
        }
        return axis == 0 ? rightX : rightY;
    }

    int calibration(int stick, int axis, int index) {
        Axis a = axisFor(stick, axis);
        return switch (index) {
            case 0 -> a.min;
            case 1 -> a.center;
            case 2 -> a.max;
            default -> throw new IndexOutOfBoundsException("no bound " + index);
        };
    }

    float extent(int stick, int axis, int index) {
        Axis a = axisFor(stick, axis);
        return index == 0 ? a.negExtent : a.posExtent;
    }
}
