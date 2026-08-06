package com.limelight.leanback;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import com.limelight.R;

import java.util.List;

/**
 * The per-app action sheet, replacing AppView's framework ContextMenu. Which actions appear
 * mirrors the old onCreateContextMenu() exactly.
 */
public class AppActionsFragment extends GuidedStepSupportFragment {
    private static final String ARG_APP_ID = "appId";
    private static final String ARG_APP_NAME = "appName";
    private static final String ARG_HIDDEN = "hidden";
    private static final String ARG_RUNNING_APP_ID = "runningAppId";
    private static final String ARG_CAN_SHORTCUT = "canShortcut";

    // Action ids carried over from AppView's old context menu constants
    public static final int ACTION_START_OR_RESUME = 1;
    public static final int ACTION_QUIT = 2;
    public static final int ACTION_START_WITH_QUIT = 4;
    public static final int ACTION_VIEW_DETAILS = 5;
    public static final int ACTION_CREATE_SHORTCUT = 6;
    public static final int ACTION_HIDE_APP = 7;

    public interface Callbacks {
        void onAppActionSelected(int appId, int actionId);
    }

    public static AppActionsFragment newInstance(int appId, String appName, boolean hidden,
                                                 int runningAppId, boolean canShortcut) {
        Bundle args = new Bundle();
        args.putInt(ARG_APP_ID, appId);
        args.putString(ARG_APP_NAME, appName);
        args.putBoolean(ARG_HIDDEN, hidden);
        args.putInt(ARG_RUNNING_APP_ID, runningAppId);
        args.putBoolean(ARG_CAN_SHORTCUT, canShortcut);

        AppActionsFragment fragment = new AppActionsFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @NonNull
    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                requireArguments().getString(ARG_APP_NAME), null, null, null);
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        Bundle args = requireArguments();
        int appId = args.getInt(ARG_APP_ID);
        int runningAppId = args.getInt(ARG_RUNNING_APP_ID);
        boolean hidden = args.getBoolean(ARG_HIDDEN);

        if (runningAppId != 0) {
            if (runningAppId == appId) {
                add(actions, ACTION_START_OR_RESUME, getString(R.string.applist_menu_resume));
                add(actions, ACTION_QUIT, getString(R.string.applist_menu_quit));
            }
            else {
                add(actions, ACTION_START_WITH_QUIT, getString(R.string.applist_menu_quit_and_start));
            }
        }

        // Only offer hiding if this isn't the running app, or it's already hidden
        if (runningAppId != appId || hidden) {
            // The old menu used a checkable item; a GuidedAction carries the state in its
            // title instead, which is clearer at a glance on a TV.
            add(actions, ACTION_HIDE_APP, getString(hidden
                    ? R.string.applist_menu_unhide_app
                    : R.string.applist_menu_hide_app));
        }

        add(actions, ACTION_VIEW_DETAILS, getString(R.string.applist_menu_details));

        if (args.getBoolean(ARG_CAN_SHORTCUT)) {
            add(actions, ACTION_CREATE_SHORTCUT, getString(R.string.applist_menu_scut));
        }
    }

    private void add(List<GuidedAction> actions, int id, String title) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(id)
                .title(title)
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        int appId = requireArguments().getInt(ARG_APP_ID);

        // Dismiss before acting so dialogs raised by the action aren't stacked behind us
        if (getFragmentManager() != null) {
            getFragmentManager().popBackStack();
        }

        if (getActivity() instanceof Callbacks) {
            ((Callbacks) getActivity()).onAppActionSelected(appId, (int) action.getId());
        }
    }
}
