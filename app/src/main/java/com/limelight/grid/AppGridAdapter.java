package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;


import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.ui.RoundedOutlineProvider;
import com.limelight.nvstream.http.ComputerDetails;

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
 *
 * <p>Extends {@link BaseAdapter} directly. There used to be a {@code GenericGridAdapter} between
 * the two, holding the cell plumbing this shared with the host grid; the host grid is now a plain
 * LinearLayout of focusable tiles with nothing to recycle, so the base class had one subclass and
 * carried a progress-spinner argument that was permanently null here.
 */
public class AppGridAdapter extends BaseAdapter {
    private static final int ART_WIDTH_PX = 300;

    private final Context context;
    private final LayoutInflater inflater;
    private final ArrayList<AppObject> itemList = new ArrayList<>();

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    /**
     * Width of a cell in pixels, resolved from resources rather than held as a dp constant so the
     * television override in values-television/ applies. Only the width is kept: it is what box
     * art has to be scaled down to fit, and the cell's own size comes from the layout.
     */
    private int cellWidthPx;

    /** Shared across every cell: all cells are the same size, so one outline serves them all. */
    private RoundedOutlineProvider outlineProvider;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppObject> allApps = new ArrayList<>();

    /** @param showHiddenApps include apps the user has hidden, for the settings-driven view */
    public AppGridAdapter(Context context, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        this.context = context;
        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;

        updateLayoutForConfiguration(context);
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
     * Re-resolves the cell geometry against the current configuration, then rebuilds the asset
     * loader around it.
     *
     * <p>There is one tile size and it is no longer a preference, so this only has to run when the
     * resources behind it could have been re-resolved - at construction, and on a configuration
     * change. The size the cells are actually laid out at comes from app_grid_item.xml; what is
     * read here is the width the box art has to be decoded for.
     */
    public void updateLayoutForConfiguration(Context context) {
        cellWidthPx = context.getResources().getDimensionPixelSize(R.dimen.app_tile_width);
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

        // Every bound cell holds a bitmap from the loader that was just thrown away
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

    /** Removes every item. */
    public void clear() {
        itemList.clear();
        allApps.clear();
    }

    /** {@inheritDoc} */
    @Override
    public int getCount() {
        return itemList.size();
    }

    /** {@inheritDoc} */
    @Override
    public Object getItem(int i) {
        return itemList.get(i);
    }

    /** {@inheritDoc} Position is the ID; apps have no stable identity of their own. */
    @Override
    public long getItemId(int i) {
        return i;
    }

    /** {@inheritDoc} Inflates or recycles a cell, then binds it. */
    @Override
    public View getView(int i, View convertView, ViewGroup viewGroup) {
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.app_grid_item, viewGroup, false);
        }

        populateView(convertView,
                convertView.findViewById(R.id.grid_image),
                convertView.findViewById(R.id.grid_text),
                convertView.findViewById(R.id.grid_overlay),
                itemList.get(i));

        return convertView;
    }

    /**
     * Binds one app to the recycled views for its cell, starting an asynchronous box art load if
     * it is not already cached.
     *
     * @param overlayView badge drawn over the artwork, for the app that is currently running
     */
    private void populateView(View parentView, ImageView imgView, TextView txtView, ImageView overlayView, AppObject obj) {
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
            // Show the running badge. Deliberately not a plain white glyph: it is drawn over box
            // art, which is frequently pale enough to swallow one. See ic_badge_running.xml.
            overlayView.setImageResource(R.drawable.ic_badge_running);
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
