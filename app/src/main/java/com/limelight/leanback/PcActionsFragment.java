package com.limelight.leanback;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import java.util.List;

/**
 * The per-PC action sheet, replacing the framework ContextMenu that a GridView long-press
 * used to open. Which actions appear mirrors the old onCreateContextMenu() exactly.
 *
 * State travels through arguments rather than fields so the fragment survives recreation.
 */
public class PcActionsFragment extends GuidedStepSupportFragment {
    private static final String ARG_UUID = "uuid";
    private static final String ARG_NAME = "name";
    private static final String ARG_STATE = "state";
    private static final String ARG_PAIRED = "paired";
    private static final String ARG_RUNNING_GAME = "runningGame";

    // Action ids carried over from PcView's old context menu constants
    public static final int ACTION_PAIR = 2;
    public static final int ACTION_DELETE = 5;
    public static final int ACTION_RESUME = 6;
    public static final int ACTION_QUIT = 7;
    public static final int ACTION_VIEW_DETAILS = 8;
    public static final int ACTION_FULL_APP_LIST = 9;
    public static final int ACTION_TEST_NETWORK = 10;
    public static final int ACTION_MANAGEMENT_PAGE = 12;

    public interface Callbacks {
        void onPcActionSelected(String uuid, int actionId);
    }

    public static PcActionsFragment newInstance(ComputerDetails details) {
        Bundle args = new Bundle();
        args.putString(ARG_UUID, details.uuid);
        args.putString(ARG_NAME, details.name);
        args.putString(ARG_STATE, details.state.name());
        args.putBoolean(ARG_PAIRED, details.pairState == PairingManager.PairState.PAIRED);
        args.putInt(ARG_RUNNING_GAME, details.runningGameId);

        PcActionsFragment fragment = new PcActionsFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private ComputerDetails.State getState() {
        return ComputerDetails.State.valueOf(requireArguments().getString(ARG_STATE));
    }

    @NonNull
    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        Bundle args = requireArguments();

        int statusRes;
        switch (getState()) {
            case ONLINE:
                statusRes = args.getBoolean(ARG_PAIRED)
                        ? R.string.pcview_menu_header_online
                        : R.string.pcview_card_not_paired;
                break;
            case OFFLINE:
                statusRes = R.string.pcview_menu_header_offline;
                break;
            case UNKNOWN:
            default:
                statusRes = R.string.pcview_menu_header_unknown;
                break;
        }

        return new GuidanceStylist.Guidance(
                args.getString(ARG_NAME), getString(statusRes), null, null);
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        Bundle args = requireArguments();
        ComputerDetails.State state = getState();

        // Mirrors the old menu: an online but unpaired PC offers only pairing, otherwise the
        // app list (plus resume/quit when a game is already running).
        if (state != ComputerDetails.State.OFFLINE
                && state != ComputerDetails.State.UNKNOWN
                && !args.getBoolean(ARG_PAIRED)) {
            add(actions, ACTION_PAIR, R.string.pcview_menu_pair_pc);
        }
        else {
            if (args.getInt(ARG_RUNNING_GAME) != 0) {
                add(actions, ACTION_RESUME, R.string.applist_menu_resume);
                add(actions, ACTION_QUIT, R.string.applist_menu_quit);
            }
            add(actions, ACTION_FULL_APP_LIST, R.string.pcview_menu_app_list);
        }

        add(actions, ACTION_MANAGEMENT_PAGE, R.string.pcview_menu_management_page);
        add(actions, ACTION_TEST_NETWORK, R.string.pcview_menu_test_network);
        add(actions, ACTION_DELETE, R.string.pcview_menu_delete_pc);
        add(actions, ACTION_VIEW_DETAILS, R.string.pcview_menu_details);
    }

    private void add(List<GuidedAction> actions, int id, int titleRes) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(id)
                .title(getString(titleRes))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        String uuid = requireArguments().getString(ARG_UUID);

        // Dismiss before acting so dialogs raised by the action aren't stacked behind us
        if (getFragmentManager() != null) {
            getFragmentManager().popBackStack();
        }

        if (getActivity() instanceof Callbacks) {
            ((Callbacks) getActivity()).onPcActionSelected(uuid, (int) action.getId());
        }
    }
}
