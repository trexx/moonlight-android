package com.limelight.ui;

import android.widget.AbsListView;

/**
 * Contract between {@link AdapterFragment} and the activity hosting it.
 *
 * <p>The fragment owns the list view but not what goes in it, so the activity supplies the layout
 * and receives the view back once it exists.
 */
public interface AdapterFragmentCallbacks {
    /** @return layout resource for the grid or list this activity wants */
    int getAdapterFragmentLayoutId();
    /** Hands the inflated view to the activity, which attaches its adapter and listeners. */
    void receiveAbsListView(AbsListView gridView);
}
