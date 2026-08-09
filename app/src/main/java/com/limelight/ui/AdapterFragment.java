package com.limelight.ui;


import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.limelight.R;

/**
 * Fragment that hosts the grid or list an activity supplies, via {@link AdapterFragmentCallbacks}.
 *
 * <p>Exists so {@link com.limelight.PcView} and {@link com.limelight.AppView} can share the same
 * grid plumbing while providing different layouts and adapters.
 */
public class AdapterFragment extends Fragment {
    private AdapterFragmentCallbacks callbacks;

    /** {@inheritDoc} Captures the hosting activity's {@link AdapterFragmentCallbacks}. */
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        callbacks = (AdapterFragmentCallbacks) activity;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(callbacks.getAdapterFragmentLayoutId(), container, false);
    }

    /** {@inheritDoc} Hands the inflated list view back to the activity. */
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        callbacks.receiveAbsListView(getView().findViewById(R.id.fragmentView));
    }
}
