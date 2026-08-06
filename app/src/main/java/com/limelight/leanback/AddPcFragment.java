package com.limelight.leanback;

import android.os.Bundle;
import android.text.InputType;

import androidx.annotation.NonNull;
import androidx.leanback.app.GuidedStepSupportFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import com.limelight.R;

import java.util.List;

/**
 * Host entry, replacing the EditText + Button layout.
 *
 * This is now the only way a PC gets added, so it is worth getting right on a D-pad:
 * GuidedAction's editable mode gives a full-width field with the on-screen keyboard handled
 * by the framework, rather than a small EditText the remote has to land on first.
 */
public class AddPcFragment extends GuidedStepSupportFragment {
    public static final int ACTION_HOST = 1;
    public static final int ACTION_ADD = 2;

    public interface Callbacks {
        void onHostEntered(String hostAddress);
    }

    @NonNull
    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(R.string.title_add_pc),
                getString(R.string.ip_hint),
                null,
                null);
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_HOST)
                .title("")
                .description(getString(R.string.ip_hint))
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI)
                .build());

        actions.add(new GuidedAction.Builder(requireContext())
                .id(ACTION_ADD)
                .title(getString(android.R.string.ok))
                .build());
    }

    @Override
    public long onGuidedActionEditedAndProceed(GuidedAction action) {
        if (action.getId() == ACTION_HOST) {
            // Move focus to the Add button so Enter on the keyboard flows straight into it
            return ACTION_ADD;
        }
        return GuidedAction.ACTION_ID_NEXT;
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() != ACTION_ADD) {
            return;
        }

        GuidedAction hostAction = findActionById(ACTION_HOST);
        String host = hostAction != null && hostAction.getTitle() != null
                ? hostAction.getTitle().toString().trim()
                : "";

        if (getActivity() instanceof Callbacks) {
            ((Callbacks) getActivity()).onHostEntered(host);
        }
    }
}
