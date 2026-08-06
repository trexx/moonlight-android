package com.limelight.grid.assets;

import android.graphics.Bitmap;

/**
 * A decoded bitmap together with the dimensions of the image it came from.
 *
 * <p>Box art is downsampled to the size the grid needs, so the original dimensions have to be
 * carried alongside: they are what tells the cache whether an already-loaded bitmap is good enough
 * for a larger cell, or has to be decoded again at a lower sample size.
 */
public class ScaledBitmap {
    public int originalWidth;
    public int originalHeight;

    public Bitmap bitmap;

    /** Creates an empty holder, to be populated after decoding. */
    public ScaledBitmap() {}

    /** @param originalWidth dimensions of the source image, before any downsampling */
    public ScaledBitmap(int originalWidth, int originalHeight, Bitmap bitmap) {
        this.originalWidth = originalWidth;
        this.originalHeight = originalHeight;
        this.bitmap = bitmap;
    }
}
