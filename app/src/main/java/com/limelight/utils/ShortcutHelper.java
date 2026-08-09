package com.limelight.utils;

import android.app.Activity;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Manages launcher shortcuts for hosts and apps.
 *
 * <p>Shortcuts outlive the things they point at, so they are disabled with an explanatory reason
 * rather than deleted when a host is removed or an app disappears — a disabled shortcut tells the
 * user why it stopped working, while a vanished one just looks like a bug.
 *
 * <p>Dynamic shortcuts are also ranked by use, so the launcher surfaces the hosts and games the
 * user actually plays.
 */
public class ShortcutHelper {

    private final ShortcutManager sm;
    private final Activity context;
    private final TvChannelHelper tvChannelHelper;

    /** @param context used both to publish shortcuts and to resolve their icons */
    public ShortcutHelper(Activity context) {
        this.context = context;
        this.sm = context.getSystemService(ShortcutManager.class);
        this.tvChannelHelper = new TvChannelHelper(context);
    }

    private void reapShortcutsForDynamicAdd() {
        List<ShortcutInfo> dynamicShortcuts = sm.getDynamicShortcuts();
        while (!dynamicShortcuts.isEmpty() && dynamicShortcuts.size() >= sm.getMaxShortcutCountPerActivity()) {
            ShortcutInfo maxRankShortcut = dynamicShortcuts.get(0);
            for (ShortcutInfo scut : dynamicShortcuts) {
                if (maxRankShortcut.getRank() < scut.getRank()) {
                    maxRankShortcut = scut;
                }
            }
            sm.removeDynamicShortcuts(Collections.singletonList(maxRankShortcut.getId()));
        }
    }

    private List<ShortcutInfo> getAllShortcuts() {
        LinkedList<ShortcutInfo> list = new LinkedList<>();
        list.addAll(sm.getDynamicShortcuts());
        list.addAll(sm.getPinnedShortcuts());
        return list;
    }

    private ShortcutInfo getInfoForId(String id) {
        List<ShortcutInfo> shortcuts = getAllShortcuts();

        for (ShortcutInfo info : shortcuts) {
            if (info.getId().equals(id)) {
                return info;
            }
        }

        return null;
    }

    private boolean isExistingDynamicShortcut(String id) {
        for (ShortcutInfo si : sm.getDynamicShortcuts()) {
            if (si.getId().equals(id)) {
                return true;
            }
        }

        return false;
    }

    /** Records a host shortcut as used, raising its launcher ranking. */
    public void reportComputerShortcutUsed(ComputerDetails computer) {
        if (getInfoForId(computer.uuid) != null) {
            sm.reportShortcutUsed(computer.uuid);
        }
    }

    /** Records a game launch, raising both the game's and its host's ranking. */
    public void reportGameLaunched(ComputerDetails computer, NvApp app) {
        tvChannelHelper.createTvChannel(computer);
        tvChannelHelper.addGameToChannel(computer, app);
    }

    /**
     * Creates or refreshes the shortcut to a host's app list.
     *
     * @param forceAdd    add it even if the shortcut list is already full
     * @param newlyPaired the host was just paired, which is worth a shortcut on its own
     */
    public void createAppViewShortcut(ComputerDetails computer, boolean forceAdd, boolean newlyPaired) {
        ShortcutInfo sinfo = new ShortcutInfo.Builder(context, computer.uuid)
                .setIntent(ServerHelper.createPcShortcutIntent(context, computer))
                .setShortLabel(computer.name)
                .setLongLabel(computer.name)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_pc_scut))
                .build();

        ShortcutInfo existingSinfo = getInfoForId(computer.uuid);
        if (existingSinfo != null) {
            // Update in place
            sm.updateShortcuts(Collections.singletonList(sinfo));
            sm.enableShortcuts(Collections.singletonList(computer.uuid));
        }

        // Reap shortcuts to make space for this if it's new
        // NOTE: This CAN'T be an else on the above if, because it's
        // possible that we have an existing shortcut but it's not a dynamic one.
        if (!isExistingDynamicShortcut(computer.uuid)) {
            // To avoid a random carousel of shortcuts popping in and out based on polling status,
            // we only add shortcuts if it's not at the limit or the user made a conscious action
            // to interact with this PC.

            if (forceAdd) {
                // This should free an entry for us to add one below
                reapShortcutsForDynamicAdd();
            }

            // We still need to check the maximum shortcut count even after reaping,
            // because there's a possibility that it could be zero.
            if (sm.getDynamicShortcuts().size() < sm.getMaxShortcutCountPerActivity()) {
                // Add a shortcut if there is room
                sm.addDynamicShortcuts(Collections.singletonList(sinfo));
            }
        }

        if (newlyPaired) {
            // Avoid hammering the channel API for each computer poll because it will throttle us
            tvChannelHelper.createTvChannel(computer);
            tvChannelHelper.requestChannelOnHomeScreen(computer);
        }
    }

    /** Creates a host's app list shortcut once that host has actually been reachable. */
    public void createAppViewShortcutForOnlineHost(ComputerDetails details) {
        createAppViewShortcut(details, false, false);
    }

    private String getShortcutIdForGame(ComputerDetails computer, NvApp app) {
        return computer.uuid + app.getAppId();
    }

    /**
     * Asks the launcher to pin a shortcut to one game.
     *
     * @return true if the request was made; the user still has to accept it
     */
    public boolean createPinnedGameShortcut(ComputerDetails computer, NvApp app, Bitmap iconBits) {
        if (sm.isRequestPinShortcutSupported()) {
            Icon appIcon;

            if (iconBits != null) {
                appIcon = Icon.createWithAdaptiveBitmap(iconBits);
            } else {
                appIcon = Icon.createWithResource(context, R.mipmap.ic_pc_scut);
            }

            ShortcutInfo sInfo = new ShortcutInfo.Builder(context, getShortcutIdForGame(computer, app))
                .setIntent(ServerHelper.createAppShortcutIntent(context, computer, app))
                .setShortLabel(app.getAppName() + " (" + computer.name + ")")
                .setIcon(appIcon)
                .build();

            return sm.requestPinShortcut(sInfo, null);
        } else {
            return false;
        }
    }

    /** Disables a host's shortcuts with a reason the launcher shows if the user taps them. */
    public void disableComputerShortcut(ComputerDetails computer, CharSequence reason) {
        tvChannelHelper.deleteChannel(computer);

        // Delete the computer shortcut itself
        if (getInfoForId(computer.uuid) != null) {
            sm.disableShortcuts(Collections.singletonList(computer.uuid), reason);
        }

        // Delete all associated app shortcuts too
        List<ShortcutInfo> shortcuts = getAllShortcuts();
        LinkedList<String> appShortcutIds = new LinkedList<>();
        for (ShortcutInfo info : shortcuts) {
            if (info.getId().startsWith(computer.uuid)) {
                appShortcutIds.add(info.getId());
            }
        }
        sm.disableShortcuts(appShortcutIds, reason);
    }

    /** Disables one game's shortcut with a user-visible reason. */
    public void disableAppShortcut(ComputerDetails computer, NvApp app, CharSequence reason) {
        tvChannelHelper.deleteProgram(computer, app);

        String id = getShortcutIdForGame(computer, app);
        if (getInfoForId(id) != null) {
            sm.disableShortcuts(Collections.singletonList(id), reason);
        }
    }

    /** Re-enables a game's shortcut after it becomes available again. */
    public void enableAppShortcut(ComputerDetails computer, NvApp app) {
        String id = getShortcutIdForGame(computer, app);
        if (getInfoForId(id) != null) {
            sm.enableShortcuts(Collections.singletonList(id));
        }
    }
}
