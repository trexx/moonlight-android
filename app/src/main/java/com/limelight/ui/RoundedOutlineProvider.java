package com.limelight.ui;

import android.graphics.Outline;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * Rounds a view's corners, and gives the focus lift something to cast a shadow from.
 *
 * <p>This is a class rather than the {@code android:clipToOutline} layout attribute because that
 * attribute arrived in API 31 and the Shield TV sits at API 30. {@link View#setClipToOutline} has
 * existed since API 21, so applying the outline from code is the only form that reaches both
 * supported boxes.
 *
 * <p>The outline does double duty: the framework clips the view's content to it, and it is also
 * the silhouette the shadow is projected from when {@code translationZ} is non-zero. A view with
 * no outline provider gets no shadow at all, so the focus lift in {@code animator/tile_focus.xml}
 * depends on this being set.
 *
 * <p>Instances are stateless apart from the radius, so tiles of the same size share one - this is
 * applied while binding grid cells, which happens on every scroll, and a provider per cell would
 * be an allocation per bind for no benefit.
 */
public class RoundedOutlineProvider extends ViewOutlineProvider {
    private final float radiusPx;

    /** @param radiusPx corner radius in pixels, already resolved from {@code tile_corner_radius} */
    public RoundedOutlineProvider(float radiusPx) {
        this.radiusPx = radiusPx;
    }

    @Override
    public void getOutline(View view, Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
    }

    /**
     * Applies this outline to a view and turns on clipping.
     *
     * <p>Both halves are needed: the provider alone rounds the shadow but leaves the content
     * square, which on box art shows as sharp corners poking out of a rounded shadow.
     */
    public void applyTo(View view) {
        view.setOutlineProvider(this);
        view.setClipToOutline(true);
    }
}
