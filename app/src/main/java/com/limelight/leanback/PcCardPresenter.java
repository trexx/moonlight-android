package com.limelight.leanback;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.leanback.widget.ImageCardView;
import androidx.leanback.widget.Presenter;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

/**
 * Renders a host PC as a Leanback card.
 *
 * Replaces PcGridAdapter.populateView(): the same state cues (dimmed when not online,
 * padlock when unpaired, offline badge) expressed with ImageCardView instead of a
 * hand-rolled GridView item layout. The old spinner for UNKNOWN state becomes a text
 * status line, which reads better at TV viewing distance than a small overlay.
 */
public class PcCardPresenter extends Presenter {
    private static final int CARD_WIDTH_DP = 200;
    private static final int CARD_HEIGHT_DP = 160;

    private static final float ALPHA_ONLINE = 1.0f;
    private static final float ALPHA_UNAVAILABLE = 0.4f;

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        Context context = new ContextThemeWrapper(parent.getContext(), R.style.AppTheme);
        ImageCardView cardView = new ImageCardView(context);

        cardView.setFocusable(true);
        cardView.setFocusableInTouchMode(true);

        float density = parent.getResources().getDisplayMetrics().density;
        cardView.setMainImageDimensions(
                Math.round(CARD_WIDTH_DP * density),
                Math.round(CARD_HEIGHT_DP * density));
        cardView.setMainImageScaleType(ImageView.ScaleType.FIT_CENTER);

        return new ViewHolder(cardView);
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        PcView.ComputerObject computer = (PcView.ComputerObject) item;
        ComputerDetails details = computer.details;
        ImageCardView cardView = (ImageCardView) viewHolder.view;
        Context context = cardView.getContext();

        cardView.setTitleText(details.name);
        cardView.setMainImage(context.getDrawable(R.drawable.ic_computer), false);

        boolean online = details.state == ComputerDetails.State.ONLINE;
        cardView.getMainImageView().setAlpha(online ? ALPHA_ONLINE : ALPHA_UNAVAILABLE);

        switch (details.state) {
            case ONLINE:
                if (details.pairState == PairingManager.PairState.NOT_PAIRED) {
                    cardView.setContentText(context.getString(R.string.pcview_card_not_paired));
                    cardView.setBadgeImage(context.getDrawable(R.drawable.ic_lock));
                }
                else {
                    cardView.setContentText(context.getString(R.string.pcview_menu_header_online));
                    cardView.setBadgeImage(null);
                }
                break;
            case OFFLINE:
                cardView.setContentText(context.getString(R.string.pcview_menu_header_offline));
                cardView.setBadgeImage(context.getDrawable(R.drawable.ic_pc_offline));
                break;
            case UNKNOWN:
            default:
                cardView.setContentText(context.getString(R.string.pcview_menu_header_unknown));
                cardView.setBadgeImage(null);
                break;
        }
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        ImageCardView cardView = (ImageCardView) viewHolder.view;

        // Release drawables so recycled cards don't retain them
        cardView.setBadgeImage(null);
        cardView.setMainImage(null);
    }
}
