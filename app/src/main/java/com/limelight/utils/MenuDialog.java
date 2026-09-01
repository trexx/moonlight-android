package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.ArrayAdapter;

import com.limelight.R;

import java.util.List;

/**
 * The one way this app shows a list of choices.
 *
 * <p>Every menu here is driven by a d-pad from across a room, and there are three of them: the
 * host menu and the app menu on the browse screen, and the in-stream menu. They used to be built
 * two different ways. The browse pair were framework {@code ContextMenu}s, which the platform
 * renders either as a popup anchored to the gesture or as a centred dialog depending on whether
 * the long press carried coordinates - so which one appeared depended on the widget the press
 * landed on and on the input device, and the two menus on the same screen did not match. The
 * in-stream menu was already an AlertDialog with a row layout of its own, because
 * {@code simple_list_item_1} is sized for a phone in the hand.
 *
 * <p>Routing all three through here settles both: the presentation is always the same dialog, and
 * the rows are always {@code menu_item.xml}. Nothing decides between forms any more, so no input
 * device can pick a different one.
 *
 * <p>Not on any per-frame path. A menu is built when it is opened; the browse screen does not
 * exist while a stream is running, and the in-stream menu is built on the Back button.
 */
public final class MenuDialog {

    /**
     * One row.
     *
     * @param action what selecting the row does, or null for a row that only dismisses - the
     *               in-stream menu's Cancel
     */
    public record Option(String label, Runnable action) {
    }

    private MenuDialog() {
    }

    /** A menu with no icon that simply goes away when it is dismissed. */
    public static void show(Activity activity, String title, List<Option> options) {
        show(activity, title, 0, options, null, null);
    }

    /**
     * Shows one menu.
     *
     * @param iconRes    drawable for the title row, or 0 for none. The host menu marks an offline
     *                   host this way.
     * @param onCancel   run when the menu is dismissed by hardware Back or a touch outside, but
     *                   not by a row selection. The in-stream menu's submenus reopen the level
     *                   above with this: each level is its own dialog with no relationship to the
     *                   one that opened it, so without it Back dropped straight to the stream.
     * @param onDismiss  run however the menu goes away, selection included. The browse screen
     *                   restarts host polling here; doing it in {@code onCancel} would leave
     *                   polling stopped whenever a row was actually chosen.
     */
    public static void show(Activity activity, String title, int iconRes, List<Option> options,
                            Runnable onCancel, Runnable onDismiss) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);

        if (iconRes != 0) {
            builder.setIcon(iconRes);
        }

        // A row layout of our own rather than android.R.layout.simple_list_item_1, which is sized
        // for a phone in the hand. These menus are driven by a controller or a remote from across
        // a room. See menu_item.xml.
        final ArrayAdapter<String> rows = new ArrayAdapter<>(activity, R.layout.menu_item);

        for (Option option : options) {
            rows.add(option.label());
        }

        // Dispatch on the row index, not on the label. The adapter is filled from options in
        // order, so which indexes options directly. Matching by label instead made two rows that
        // happen to share text both run the first one's action - which the in-stream menu's Back
        // rows and its repeated pad-audio state words would otherwise do.
        builder.setAdapter(rows, (dialog, which) -> {
            Runnable action = options.get(which).action();
            if (action != null) {
                action.run();
            }
        });

        // Fires on hardware Back and on a touch outside, but not on a row selection, which
        // dismisses rather than cancels.
        if (onCancel != null) {
            builder.setOnCancelListener(dialog -> onCancel.run());
        }

        if (onDismiss != null) {
            builder.setOnDismissListener(dialog -> onDismiss.run());
        }

        builder.show();
    }
}
