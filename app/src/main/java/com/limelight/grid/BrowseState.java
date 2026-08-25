package com.limelight.grid;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the browse screen should be showing: which hosts exist, which one is selected, and what
 * belongs in the content area beneath the host band.
 *
 * <p>This is deliberately free of any Android type. Before the two browsing activities were
 * merged, the same decisions were spread across {@code PcView.updateComputer},
 * {@code AppView.notifyComputerUpdated} and {@code AppView.updateUiWithServerinfo}, each reached
 * only from a bound service callback on a real device, and so none of it could be exercised
 * without one. Extracting it follows the precedent set by {@code VideoStats},
 * {@code KeyMapper} and {@code StickCalibration}.
 *
 * <p>No {@code LimeLog} calls belong here: it is backed by {@code android.util.Log}, whose stub
 * throws under a JVM test.
 *
 * <p>Not thread safe, and not required to be — every mutation comes from a poll result that the
 * caller has already posted to the main thread.
 */
public final class BrowseState {

    /** What the area below the host band should show. */
    public enum Content {
        /** Nothing discovered yet. Shows the discovery-in-progress message. */
        NO_HOSTS,
        /** Hosts exist but none is selected, because none is reachable and paired. */
        NO_SELECTION,
        /** The selected host is still being probed; its reachability is not yet known. */
        HOST_PROBING,
        /** The selected host did not answer. */
        HOST_OFFLINE,
        /** The selected host answered but this client is not paired with it. */
        HOST_UNPAIRED,
        /** The selected host is reachable and paired: show its apps. */
        APPS
    }

    /**
     * Hosts, sorted by name. Held sorted rather than sorted on read because the host band is
     * rebuilt from this on every poll result, and the order has to be stable between rebuilds or
     * focus lands on a different tile than the one the user was on.
     */
    private final ArrayList<ComputerDetails> hosts = new ArrayList<>();

    private String selectedUuid;

    /**
     * A host that should be selected as soon as it turns out to be selectable, named before
     * anything is known about it — the uuid a launcher shortcut carried, or the one being browsed
     * when the app was last closed. Cleared once honoured, or once some other host is chosen.
     *
     * <p>It exists because the wanted host arrives through the same poll results as every other
     * one, and without it whichever host answered first would win.
     */
    private String preferredUuid;

    /**
     * Names the host to settle on once it is known, overriding the fallback of selecting the
     * first reachable one. Has no effect if that host never appears or never becomes reachable.
     */
    public void setPreferredUuid(String uuid) {
        preferredUuid = uuid;
    }

    /**
     * Records a poll result, adding the host or replacing what was known about it.
     *
     * <p>Applies the two cases where an update has to revoke the current selection: a host that
     * stops answering, and one this client is no longer paired with. A host merely being
     * re-probed ({@link ComputerDetails.State#UNKNOWN}) does <em>not</em> revoke it — that state
     * is entered routinely during normal polling, and dropping the user out of an app grid every
     * time a poll is in flight would make the screen unusable.
     *
     * <p>It will also fill an <em>absent</em> selection, so that a single-host setup lands on its
     * apps instead of on an empty screen. It only ever fills an absent one, so a second host
     * coming online cannot pull the user off the one they are already browsing.
     *
     * @return true if the selection changed, which is the caller's cue to restart the app list
     *         poller against a different host
     */
    public boolean update(ComputerDetails details) {
        String before = selectedUuid;

        int existing = indexOf(details.uuid);
        if (existing >= 0) {
            hosts.set(existing, details);
        }
        else {
            hosts.add(details);
            sort();
        }

        if (details.uuid.equalsIgnoreCase(selectedUuid) && !isSelectable(details)
                && details.state != ComputerDetails.State.UNKNOWN) {
            selectedUuid = null;
        }

        // The preferred host has now been seen and cannot be browsed. Give up on it, or a host
        // that is simply switched off would block every other one from ever being selected.
        if (equalUuid(preferredUuid, details.uuid) && !isSelectable(details)
                && details.state != ComputerDetails.State.UNKNOWN) {
            preferredUuid = null;
        }

        if (selectedUuid == null && isSelectable(details)
                && (preferredUuid == null || equalUuid(preferredUuid, details.uuid))) {
            selectedUuid = details.uuid;
            preferredUuid = null;
        }

        return !equalUuid(before, selectedUuid);
    }

    /**
     * Forgets a host entirely, as when the user deletes it.
     *
     * @return true if it was present
     */
    public boolean remove(String uuid) {
        int index = indexOf(uuid);
        if (index < 0) {
            return false;
        }

        hosts.remove(index);

        if (equalUuid(uuid, selectedUuid)) {
            selectedUuid = null;
        }

        return true;
    }

    /**
     * Selects a host by uuid, which is what a click on the host band does.
     *
     * <p>A host that is offline or unpaired cannot be selected: there is nothing to show for it,
     * and the caller offers pairing or the host menu instead. Passing null clears the selection.
     *
     * @return true if the selection changed
     */
    public boolean select(String uuid) {
        // An explicit choice supersedes whatever was asked for at startup.
        preferredUuid = null;

        if (uuid == null) {
            boolean changed = selectedUuid != null;
            selectedUuid = null;
            return changed;
        }

        int index = indexOf(uuid);
        if (index < 0 || !isSelectable(hosts.get(index)) || equalUuid(uuid, selectedUuid)) {
            return false;
        }

        selectedUuid = hosts.get(index).uuid;
        return true;
    }

    /** @return the selected host's uuid, or null if none is selected */
    public String getSelectedUuid() {
        return selectedUuid;
    }

    /** @return the selected host, or null if none is selected */
    public ComputerDetails getSelected() {
        int index = indexOf(selectedUuid);
        return index < 0 ? null : hosts.get(index);
    }

    /** @return the known hosts, sorted by name; the returned list must not be modified */
    public List<ComputerDetails> getHosts() {
        return Collections.unmodifiableList(hosts);
    }

    /** @return what the content area below the host band should show */
    public Content getContent() {
        if (hosts.isEmpty()) {
            return Content.NO_HOSTS;
        }

        ComputerDetails selected = getSelected();
        if (selected == null) {
            return Content.NO_SELECTION;
        }

        return switch (selected.state) {
            case OFFLINE -> Content.HOST_OFFLINE;
            case UNKNOWN -> Content.HOST_PROBING;
            case ONLINE -> selected.pairState == PairingManager.PairState.PAIRED
                    ? Content.APPS
                    : Content.HOST_UNPAIRED;
        };
    }

    /**
     * @return true if the host can be browsed, i.e. it answered and we are paired with it.
     *         Anything other than {@code PAIRED} counts as unpaired here, including the transient
     *         results of a failed pairing attempt.
     */
    public static boolean isSelectable(ComputerDetails details) {
        return details.state == ComputerDetails.State.ONLINE
                && details.pairState == PairingManager.PairState.PAIRED;
    }

    private int indexOf(String uuid) {
        if (uuid == null) {
            return -1;
        }

        for (int i = 0; i < hosts.size(); i++) {
            if (uuid.equalsIgnoreCase(hosts.get(i).uuid)) {
                return i;
            }
        }

        return -1;
    }

    /** Host uuids arrive from the host itself and their case is not guaranteed stable. */
    private static boolean equalUuid(String lhs, String rhs) {
        return lhs == null ? rhs == null : lhs.equalsIgnoreCase(rhs);
    }

    /** Case-insensitive by name, matching what the host grid sorted by before the merge. */
    private void sort() {
        hosts.sort(Comparator.comparing(details -> details.name.toLowerCase(Locale.getDefault())));
    }
}
