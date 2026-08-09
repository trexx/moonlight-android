package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
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
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 100;
    private static final int LARGE_WIDTH_DP = 150;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();

    /** @param showHiddenApps include apps the user has hidden, for the settings-driven view */
    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

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
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        if (prefs.smallIconMode) {
            return R.layout.app_grid_item_small;
        }
        else {
            return R.layout.app_grid_item;
        }
    }

    /** Re-reads the cell size and box art preferences, then rebuilds the layout. */
    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp;

        if (prefs.smallIconMode) {
            dp = SMALL_WIDTH_DP;
        }
        else {
            dp = LARGE_WIDTH_DP;
        }

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
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

        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    /** Cancels pending box art loads, e.g. when leaving the screen. */
    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                return lhs.app.getAppName().toLowerCase(Locale.getDefault()).compareTo(rhs.app.getAppName().toLowerCase(Locale.getDefault()));
            }
        });
    }

    /** Adds an app and re-sorts, so the grid order is stable regardless of arrival order. */
    public void addApp(AppView.AppObject app) {
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
    public void removeApp(AppView.AppObject app) {
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
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
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
