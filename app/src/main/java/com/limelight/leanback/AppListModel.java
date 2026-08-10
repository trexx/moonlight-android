package com.limelight.leanback;

import android.content.Context;
import android.graphics.BitmapFactory;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the app list, hidden-app filtering and the box art loader.
 *
 * This is AppGridAdapter with the BaseAdapter half removed: the data rules (sorting, the
 * allApps/visible split, art scaling) are unchanged and moved here so the Leanback fragment
 * can stay a pure renderer. The box art pipeline is untouched - it still fetches through
 * NvHTTP with the paired client certificate, which no general-purpose image loader can do.
 */
public class AppListModel {
    private static final int ART_WIDTH_PX = 300;
    private static final int SMALL_WIDTH_DP = 100;
    private static final int LARGE_WIDTH_DP = 150;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private final List<AppView.AppObject> allApps = new ArrayList<>();
    private final List<AppView.AppObject> visibleApps = new ArrayList<>();
    private final Set<Integer> hiddenAppIds = new HashSet<>();

    private CachedAppAssetLoader loader;

    public AppListModel(Context context, ComputerDetails computer, String uniqueId,
                        boolean showHiddenApps, boolean smallIconMode) {
        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateScaling(context, smallIconMode);
    }

    /** Rebuilds the asset loader for the current card size. */
    public void updateScaling(Context context, boolean smallIconMode) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp = smallIconMode ? SMALL_WIDTH_DP : LARGE_WIDTH_DP;

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            cancelQueuedOperations();
        }

        loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));
    }

    public CachedAppAssetLoader getAssetLoader() {
        return loader;
    }

    /** Cancels pending box art loads, e.g. when leaving the screen. */
    public void cancelQueuedOperations() {
        if (loader != null) {
            loader.cancelForegroundLoads();
            loader.cancelBackgroundLoads();
            loader.freeCacheMemory();
        }
    }

    public List<AppView.AppObject> getVisibleApps() {
        return visibleApps;
    }

    public int getCount() {
        return visibleApps.size();
    }

    private static void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, new Comparator<AppView.AppObject>() {
            @Override
            public int compare(AppView.AppObject lhs, AppView.AppObject rhs) {
                return lhs.app.getAppName().toLowerCase(Locale.getDefault()).compareTo(rhs.app.getAppName().toLowerCase(Locale.getDefault()));
            }
        });
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        hiddenAppIds.clear();
        hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the visible list with the new hidden app set
            visibleApps.clear();
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    visibleApps.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }
    }

    public void addApp(AppView.AppObject app) {
        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);
        sortList(allApps);

        // Add the app to the visible data if it's not hidden
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            if (loader != null) {
                loader.queueCacheLoad(app.app);
            }

            visibleApps.add(app);
            sortList(visibleApps);
        }
    }

    /** Removes an app from the grid. */
    public void removeApp(AppView.AppObject app) {
        visibleApps.remove(app);
        allApps.remove(app);
    }

    public List<AppView.AppObject> getAllApps() {
        return allApps;
    }

    public void clear() {
        visibleApps.clear();
        allApps.clear();
    }
}
