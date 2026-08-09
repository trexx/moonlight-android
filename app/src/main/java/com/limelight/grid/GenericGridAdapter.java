package com.limelight.grid;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;

/**
 * Base adapter for the app and host grids.
 *
 * <p>Holds the common view structure — icon, label, progress spinner and an overlay badge — and
 * leaves subclasses to fill it in via {@link #populateView}. The spinner and overlay exist because
 * both grids show items whose artwork and state arrive asynchronously.
 */
public abstract class GenericGridAdapter<T> extends BaseAdapter {
    protected final Context context;
    private int layoutId;
    final ArrayList<T> itemList = new ArrayList<>();
    private final LayoutInflater inflater;

    GenericGridAdapter(Context context, int layoutId) {
        this.context = context;
        this.layoutId = layoutId;

        this.inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    void setLayoutId(int layoutId) {
        if (layoutId != this.layoutId) {
            this.layoutId = layoutId;

            // Force the view to be redrawn with the new layout
            notifyDataSetInvalidated();
        }
    }

    /** Removes every item. */
    public void clear() {
        itemList.clear();
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

    /** {@inheritDoc} Position is the ID; items have no stable identity of their own. */
    @Override
    public long getItemId(int i) {
        return i;
    }

    /**
     * Binds one item to the recycled views for its cell.
     *
     * @param prgView     progress spinner, shown while the item's artwork is still loading
     * @param overlayView badge drawn over the icon, for state such as "running" or "offline"
     */
    public abstract void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, T obj);

    /** {@inheritDoc} Inflates or recycles a cell, then hands it to {@link #populateView}. */
    @Override
    public View getView(int i, View convertView, ViewGroup viewGroup) {
        if (convertView == null) {
            convertView = inflater.inflate(layoutId, viewGroup, false);
        }

        ImageView imgView = convertView.findViewById(R.id.grid_image);
        ImageView overlayView = convertView.findViewById(R.id.grid_overlay);
        TextView txtView = convertView.findViewById(R.id.grid_text);
        ProgressBar prgView = convertView.findViewById(R.id.grid_spinner);

        populateView(convertView, imgView, prgView, txtView, overlayView, itemList.get(i));

        return convertView;
    }
}
