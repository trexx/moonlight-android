package com.limelight.leanback;

import android.os.Bundle;

import androidx.leanback.app.VerticalGridSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.DiffCallback;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.VerticalGridPresenter;

import com.limelight.AppView;
import com.limelight.grid.assets.CachedAppAssetLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * The app grid. The activity owns the app list, hidden-app filtering and the asset loader;
 * this only renders what it is handed.
 */
public class AppGridFragment extends VerticalGridSupportFragment {
    private static final int NUM_COLUMNS = 5;

    private static final DiffCallback<AppView.AppObject> DIFF =
            new DiffCallback<AppView.AppObject>() {
                @Override
                public boolean areItemsTheSame(AppView.AppObject oldItem, AppView.AppObject newItem) {
                    return oldItem.app.getAppId() == newItem.app.getAppId();
                }

                @Override
                public boolean areContentsTheSame(AppView.AppObject oldItem, AppView.AppObject newItem) {
                    return oldItem.isHidden == newItem.isHidden
                            && oldItem.isRunning == newItem.isRunning
                            && oldItem.app.getAppName().equals(newItem.app.getAppName());
                }
            };

    private final List<AppView.AppObject> apps = new ArrayList<>();

    private ArrayObjectAdapter adapter;
    private CachedAppAssetLoader assetLoader;
    private int runningAppId;

    public interface Callbacks {
        void onAppClicked(AppView.AppObject app);
        void onAppLongClicked(AppView.AppObject app);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        VerticalGridPresenter gridPresenter = new VerticalGridPresenter();
        gridPresenter.setNumberOfColumns(NUM_COLUMNS);
        setGridPresenter(gridPresenter);

        rebuildAdapter();

        setOnItemViewClickedListener(new OnItemViewClickedListener() {
            @Override
            public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                      RowPresenter.ViewHolder rowViewHolder, Row row) {
                if (getActivity() instanceof Callbacks) {
                    ((Callbacks) getActivity()).onAppClicked((AppView.AppObject) item);
                }
            }
        });
    }

    /**
     * The presenter captures the asset loader and the running app id, so changing either
     * means rebuilding it. Called once the activity has bound to the computer manager.
     */
    public void setAssetLoader(CachedAppAssetLoader assetLoader) {
        this.assetLoader = assetLoader;
        rebuildAdapter();
    }

    public void setRunningAppId(int runningAppId) {
        if (this.runningAppId != runningAppId) {
            this.runningAppId = runningAppId;
            rebuildAdapter();
        }
    }

    private void rebuildAdapter() {
        if (assetLoader == null) {
            // Nothing to render until the activity supplies a loader
            return;
        }

        adapter = new ArrayObjectAdapter(new AppCardPresenter(assetLoader, runningAppId));
        adapter.addAll(0, apps);
        setAdapter(adapter);
    }

    public int getCount() {
        return apps.size();
    }

    /**
     * Finds the box art ImageView of the currently-bound card for this app, or null if the
     * card isn't attached. Used for the pinned-shortcut action, which needs the loaded
     * bitmap - the old code reached into the context menu's target view for this.
     */
    public android.widget.ImageView findCardImageView(int appId) {
        if (getView() == null) {
            return null;
        }

        androidx.leanback.widget.VerticalGridView gridView =
                getView().findViewById(androidx.leanback.R.id.browse_grid);
        if (gridView == null) {
            return null;
        }

        for (int i = 0; i < gridView.getChildCount(); i++) {
            android.view.View child = gridView.getChildAt(i);
            int position = gridView.getChildAdapterPosition(child);
            if (position < 0 || position >= apps.size()) {
                continue;
            }
            if (apps.get(position).app.getAppId() == appId) {
                return child.findViewById(com.limelight.R.id.grid_image);
            }
        }
        return null;
    }

    /** Replaces the visible app list wholesale, diffing so focus survives a poll. */
    public void setApps(List<AppView.AppObject> newApps) {
        apps.clear();
        apps.addAll(newApps);

        if (adapter != null) {
            adapter.setItems(new ArrayList<>(apps), DIFF);
        }
        else {
            rebuildAdapter();
        }
    }
}
