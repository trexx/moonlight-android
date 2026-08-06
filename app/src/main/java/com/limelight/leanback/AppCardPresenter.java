package com.limelight.leanback;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.leanback.widget.BaseCardView;
import androidx.leanback.widget.Presenter;

import com.limelight.AppView;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;

/**
 * Renders an app as a Leanback card.
 *
 * The card wraps the existing app_grid_item layout rather than using ImageCardView, because
 * {@link CachedAppAssetLoader#populateImageView} drives a specific ImageView/TextView pair:
 * it swaps an AsyncDrawable onto the ImageView to track the in-flight load, and toggles the
 * TextView to show the app name when the host only returned placeholder art. ImageCardView
 * exposes neither view usably, so wrapping the existing layout keeps the authenticated box
 * art pipeline working untouched while still getting Leanback focus and scaling behaviour.
 */
public class AppCardPresenter extends Presenter {
    private final CachedAppAssetLoader assetLoader;
    private final int runningAppId;

    public AppCardPresenter(CachedAppAssetLoader assetLoader, int runningAppId) {
        this.assetLoader = assetLoader;
        this.runningAppId = runningAppId;
    }

    /** Leanback card container hosting the existing grid item layout. */
    private static class AppCardView extends BaseCardView {
        AppCardView(Context context) {
            super(context, null, androidx.leanback.R.style.Widget_Leanback_BaseCardViewStyle);

            setFocusable(true);
            setFocusableInTouchMode(true);
            LayoutInflater.from(context).inflate(R.layout.app_grid_item, this);
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(new AppCardView(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        AppView.AppObject app = (AppView.AppObject) item;
        View card = viewHolder.view;

        ImageView imageView = card.findViewById(R.id.grid_image);
        TextView textView = card.findViewById(R.id.grid_text);
        ImageView overlayView = card.findViewById(R.id.grid_overlay);

        // Running app gets the play badge; hidden apps are dimmed
        if (app.app.getAppId() == runningAppId) {
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }

        card.setAlpha(app.isHidden ? 0.4f : 1.0f);

        assetLoader.populateImageView(app.app, imageView, textView);
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        ImageView imageView = viewHolder.view.findViewById(R.id.grid_image);
        if (imageView != null) {
            // Drops the AsyncDrawable so a recycled card doesn't keep a finished load alive
            imageView.setImageDrawable(null);
        }
    }
}
