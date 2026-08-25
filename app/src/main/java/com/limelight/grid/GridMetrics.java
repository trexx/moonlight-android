package com.limelight.grid;

/**
 * Sizing arithmetic for the app grid, split out of {@link AppGridAdapter} so it can be exercised
 * without a {@code Context}.
 *
 * <p>Pure and stateless — same reasoning as {@link BrowseState}, and the same prohibition on
 * {@code LimeLog}.
 */
public final class GridMetrics {

    /**
     * How much box art should be shrunk before it is cached in memory.
     *
     * <p>Box art arrives from the host at a fixed width whatever the cell it is going into, and
     * decoding it at full size costs both the decode and the resident bitmap. The divisor is what
     * {@code BitmapFactory.Options.inSampleSize} is derived from.
     *
     * <p>Clamped at 1.0 so art is never scaled <em>up</em> before draw time: enlarging it early
     * costs memory to store a blurrier image than the one the GPU would produce filtering it at
     * draw. That matters most on the small-icon setting at low density, which is the one case
     * where the cell is narrower than the source.
     *
     * <p>Both widths are in pixels. The cell width used to be passed in dp and converted here
     * against the display density, which was the same arithmetic written out — the cell size is a
     * resource, so the framework has already resolved it to pixels by the time it is read.
     *
     * @param artWidthPx  width of the artwork as the host serves it
     * @param cellWidthPx width of the cell it has to fill
     * @return a divisor of 1.0 or greater
     */
    public static double artScalingDivisor(int artWidthPx, int cellWidthPx) {
        if (cellWidthPx <= 0) {
            return 1.0;
        }

        double divisor = (double) artWidthPx / cellWidthPx;
        return divisor < 1.0 ? 1.0 : divisor;
    }

    private GridMetrics() {
    }
}
