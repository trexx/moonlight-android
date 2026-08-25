package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;


import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.ui.RoundedOutlineProvider;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for the app grid, including box art loading and the hidden-app filter.
 *
 * <p>Box art is fetched through {@link com.limelight.grid.assets.CachedAppAssetLoader}, whose
 * loads are cancelled when cells are recycled — a grid that scrolls faster than the network would
 * otherwise queue work for cells that are long gone.
 */
@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppObject> {
    private static final int ART_WIDTH_PX = 300;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    /**
     * Cell geometry, resolved from resources rather than held as dp constants so the television
     * overrides in values-television/ apply. Rewritten by
     * {@link #updateLayoutWithPreferences} whenever the small-icon setting changes.
     */
    private int cellWidthPx, cellHeightPx;

    /** Shared across every cell: all cells are the same size, so one outline serves them all. */
    private RoundedOutlineProvider outlineProvider;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppObject> allApps = new ArrayList<>();

    /** @param showHiddenApps include apps the user has hidden, for the settings-driven view */
    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, R.layout.app_grid_item);

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutWithPreferences(context, prefs);
    }

    /**
     * Applies the hidden-app selection.
     *
     * @param hideImmediately remove them from the grid now rather than on the next rebuild, which
     *                        is what lets the user hide several apps in one pass
     */
    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            itemList.clear();
            for (AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    /**
     * Re-reads the cell size and box art preferences, then rebuilds the asset loader around them.
     *
     * <p>There is one cell layout now, not one per setting: small icon mode changes the cell's
     * measured size, which is applied per bind in {@link #populateView}, rather than swapping in a
     * second copy of the same layout at a different scale.
     */
    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        cellWidthPx = context.getResources().getDimensionPixelSize(prefs.smallIconMode
                ? R.dimen.app_tile_width_small : R.dimen.app_tile_width_large);
        cellHeightPx = context.getResources().getDimensionPixelSize(prefs.smallIconMode
                ? R.dimen.app_tile_height_small : R.dimen.app_tile_height_large);
        outlineProvider = new RoundedOutlineProvider(
                context.getResources().getDimensionPixelSize(R.dimen.tile_corner_radius));

        double scalingDivisor = GridMetrics.artScalingDivisor(ART_WIDTH_PX, cellWidthPx);
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));

        // Cells already on screen were measured for the previous size
        notifyDataSetInvalidated();
    }

    /** Cancels pending box art loads, e.g. when leaving the screen. */
    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private static void sortList(List<AppObject> list) {
        Collections.sort(list, new Comparator<AppObject>() {
            @Override
            public int compare(AppObject lhs, AppObject rhs) {
                return lhs.app.getAppName().toLowerCase(Locale.getDefault()).compareTo(rhs.app.getAppName().toLowerCase(Locale.getDefault()));
            }
        });
    }

    /** Adds an app and re-sorts, so the grid order is stable regardless of arrival order. */
    public void addApp(AppObject app) {
        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);
        sortList(allApps);

        // Add the app to the adapter data if it's not hidden
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);

            // Add the app to our sorted list
            itemList.add(app);
            sortList(itemList);
        }
    }

    /** Removes an app from the grid. */
    public void removeApp(AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    /** {@inheritDoc} Also cancels any box art loads still queued for the removed cells. */
    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    /** {@inheritDoc} Binds name and box art, starting an asynchronous load if it isn't cached. */
    @Override
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, AppObject obj) {
        // Cell size is a preference, not a layout, so it is applied here rather than by inflating
        // a second copy of the layout at a different scale.
        ViewGroup.LayoutParams params = parentView.getLayoutParams();
        if (params != null && (params.width != cellWidthPx || params.height != cellHeightPx)) {
            params.width = cellWidthPx;
            params.height = cellHeightPx;
            parentView.setLayoutParams(params);
        }

        // Rounds the artwork and the label scrim along with the tile's own background. Set once
        // per view rather than per bind: this runs on every scroll, and both calls invalidate.
        if (parentView.getOutlineProvider() != outlineProvider) {
            outlineProvider.applyTo(parentView);
        }

        // Cells are recycled, and BrowseActivity raises the selected one by hand because GridView
        // cells cannot hold focus. A cell recycled while raised would arrive still scaled.
        parentView.setScaleX(1f);
        parentView.setScaleY(1f);
        parentView.setTranslationZ(0f);

        // Let the cached asset loader handle it
        loader.populateImageView(obj.app, imgView, txtView);

        if (obj.isRunning) {
            // Show the play button overlay
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
        }

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else {
            parentView.setAlpha(1.0f);
        }
    }
}
