package com.limelight.leanback;

import android.annotation.SuppressLint;
import android.os.Bundle;

import androidx.leanback.app.VerticalGridSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.DiffCallback;
import androidx.leanback.widget.OnItemViewClickedListener;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.Row;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.VerticalGridPresenter;

import com.limelight.PcView;
import com.limelight.nvstream.http.ComputerDetails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The host PC grid.
 *
 * Replaces the GridView + PcGridAdapter + AdapterFragment trio. The activity owns all the
 * logic (polling, pairing, deletion) and drives this through addOrUpdate/remove.
 */
public class PcGridFragment extends VerticalGridSupportFragment {
    private static final int NUM_COLUMNS = 4;

    /** Matches PcGridAdapter's old ordering: case-insensitive by name. */
    private static final Comparator<PcView.ComputerObject> BY_NAME = (lhs, rhs) ->
            lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());

    /**
     * Identity is the UUID, so a PC coming online updates its card in place instead of being
     * torn down and rebuilt - which would drop D-pad focus on every poll cycle.
     */
    private static final DiffCallback<PcView.ComputerObject> DIFF =
            new DiffCallback<PcView.ComputerObject>() {
                @Override
                public boolean areItemsTheSame(PcView.ComputerObject oldItem,
                                               PcView.ComputerObject newItem) {
                    return oldItem.details.uuid.equals(newItem.details.uuid);
                }

                // State and PairState are enums, so reference equality is both correct and
                // the preferred comparison. Lint's DiffUtilEquals check can't tell.
                @SuppressLint("DiffUtilEquals")
                @Override
                public boolean areContentsTheSame(PcView.ComputerObject oldItem,
                                                  PcView.ComputerObject newItem) {
                    ComputerDetails a = oldItem.details;
                    ComputerDetails b = newItem.details;
                    return a.state == b.state
                            && a.pairState == b.pairState
                            && a.runningGameId == b.runningGameId
                            && a.name.equals(b.name);
                }
            };

    /** Source of truth; the adapter is refreshed from this so ordering stays stable. */
    private final List<PcView.ComputerObject> computers = new ArrayList<>();

    private ArrayObjectAdapter adapter;

    public interface Callbacks {
        void onComputerClicked(PcView.ComputerObject computer);
        void onComputerLongClicked(PcView.ComputerObject computer);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        VerticalGridPresenter gridPresenter = new VerticalGridPresenter();
        gridPresenter.setNumberOfColumns(NUM_COLUMNS);
        setGridPresenter(gridPresenter);

        adapter = new ArrayObjectAdapter(new PcCardPresenter());
        setAdapter(adapter);
        publish();

        setOnItemViewClickedListener(new OnItemViewClickedListener() {
            @Override
            public void onItemClicked(Presenter.ViewHolder itemViewHolder, Object item,
                                      RowPresenter.ViewHolder rowViewHolder, Row row) {
                if (getActivity() instanceof Callbacks) {
                    ((Callbacks) getActivity()).onComputerClicked((PcView.ComputerObject) item);
                }
            }
        });
    }

    private void publish() {
        if (adapter != null) {
            adapter.setItems(new ArrayList<>(computers), DIFF);
        }
    }

    public int getCount() {
        return computers.size();
    }

    /** Adds the computer, or refreshes the existing card if we already know this UUID. */
    public void addOrUpdateComputer(PcView.ComputerObject computer) {
        for (int i = 0; i < computers.size(); i++) {
            if (computers.get(i).details.uuid.equals(computer.details.uuid)) {
                computers.set(i, computer);
                publish();
                return;
            }
        }

        computers.add(computer);
        Collections.sort(computers, BY_NAME);
        publish();
    }

    public void removeComputer(String uuid) {
        for (int i = 0; i < computers.size(); i++) {
            if (computers.get(i).details.uuid.equals(uuid)) {
                computers.remove(i);
                publish();
                return;
            }
        }
    }
}
