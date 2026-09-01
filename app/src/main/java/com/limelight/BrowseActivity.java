package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.grid.AppObject;
import com.limelight.grid.BrowseMenuLayout;
import com.limelight.grid.BrowseState;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.MenuDialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The browse screen: hosts along the top, the selected host's apps below, and a navigation rail
 * for everything else.
 *
 * <p>Replaces the former {@code PcView} and {@code AppView}, which were separate activities. They
 * were merged because the split forced a screen change to switch host, and because both bound the
 * same {@link ComputerManagerService} and installed their own
 * {@link ComputerManagerListener} — the binder accepts only one listener, so having two activities
 * that each wanted it meant neither could be open while the other polled.
 *
 * <p>Hosts are owned by the service, which polls their reachability and pairing state in the
 * background; everything drawn here reflects state it publishes. What that state <em>means</em>
 * for the screen lives in {@link BrowseState}, deliberately separated so it can be exercised
 * without a device.
 *
 * <p>Pairing, unpairing, waking and removing hosts, and starting, quitting and hiding apps, are
 * all driven from the host menu and the app menu, which is why so much of this class is menu and
 * dialog handling rather than view code. Both are shown by {@link com.limelight.utils.MenuDialog},
 * which the in-stream menu uses too, and the rows each offers are decided by
 * {@link BrowseMenuLayout}, which is where they can be tested.
 *
 * <p>Latency: nothing here runs while a stream exists. The activity is torn down before
 * {@link Game} starts and rebuilt after it finishes.
 */
public class BrowseActivity extends Activity {

    /**
     * Intent extras. These kept their names and values when they moved off {@code AppView}, since
     * they are also written by launcher shortcuts created by earlier versions and read back here
     * when one of those is tapped.
     */
    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    /** Remembers which host was being browsed, so a relaunch returns to it rather than guessing. */
    private final static String SELECTION_PREF_FILENAME = "BrowseSelection";
    private final static String SELECTION_PREF_KEY = "SelectedUuid";

    private LinearLayout navRail;
    private LinearLayout hostBand;
    private GridView appGrid;
    private TextView appSectionLabel;
    private TextView contentMessage;

    private final BrowseState browseState = new BrowseState();
    private AppGridAdapter appGridAdapter;
    private ShortcutHelper shortcutHelper;

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;

    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private boolean suspendGridUpdates;
    private boolean showHiddenApps;

    private String lastRawApplist;
    private int lastRunningAppId;
    private final HashSet<Integer> hiddenAppIds = new HashSet<>();

    /**
     * The tile the app grid currently has selected, held so it can be dropped again when
     * selection moves. See {@link #liftTile}.
     */
    private View liftedTile;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(BrowseActivity.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    /**
     * {@inheritDoc}
     *
     * <p>The GL renderer probe has to happen before anything else is shown: it needs its own
     * content view, and {@code MediaCodecHelper} reads the cached result when a stream starts.
     * It runs once per firmware build, so this path is not normally taken.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    /**
     * Finishes setup once the GL renderer probe above has settled, which can be a later callback
     * than {@code onCreate}.
     */
    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);

        // A shortcut, the TV channel or ShortcutTrampoline can name the host to open. Otherwise
        // return to whichever host was last being browsed. Either way the host is not known yet -
        // the service has reported nothing - so this is recorded as a preference that the first
        // matching poll result honours.
        String requestedUuid = getIntent().getStringExtra(UUID_EXTRA);
        if (requestedUuid == null) {
            requestedUuid = getSharedPreferences(SELECTION_PREF_FILENAME, MODE_PRIVATE)
                    .getString(SELECTION_PREF_KEY, null);
        }
        browseState.setPreferredUuid(requestedUuid);

        bindService(new Intent(BrowseActivity.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        initializeViews();
    }

    /** Inflates the screen and wires up the rail, the host band and the app grid. */
    private void initializeViews() {
        setContentView(R.layout.activity_browse);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        navRail = findViewById(R.id.navRail);
        hostBand = findViewById(R.id.hostBand);
        appGrid = findViewById(R.id.appGrid);
        appSectionLabel = findViewById(R.id.appSectionLabel);
        contentMessage = findViewById(R.id.contentMessage);

        buildRail();
        wireAppGrid();

        rebuild();
    }

    // ------------------------------------------------------------------------------------------
    // Navigation rail
    // ------------------------------------------------------------------------------------------

    /**
     * Fills the rail. Inflated rather than declared in the layout because each item needs its own
     * icon and label and {@code <include>} can supply neither.
     *
     * <p>Only the two destinations that leave this screen. A "Hosts" entry was tried and removed:
     * the host band is already on screen directly above the grid and a press of "up" reaches it,
     * so a rail item that only moved focus there was a third way to do nothing new.
     */
    private void buildRail() {
        addRailItem(R.drawable.ic_add, R.string.rail_add_pc, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(BrowseActivity.this, AddComputerManually.class));
            }
        });
        addRailItem(R.drawable.ic_settings, R.string.title_settings, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(BrowseActivity.this, StreamSettings.class));
            }
        });
    }

    private void addRailItem(int iconRes, int labelRes, View.OnClickListener onClick) {
        View item = LayoutInflater.from(this).inflate(R.layout.rail_item, navRail, false);

        ((ImageView) item.findViewById(R.id.railIcon)).setImageResource(iconRes);
        ((TextView) item.findViewById(R.id.railLabel)).setText(labelRes);

        // The label is clipped rather than hidden while the rail is collapsed, so it still needs
        // to describe the item to a screen reader.
        item.setContentDescription(getResources().getString(labelRes));
        item.setOnClickListener(onClick);
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                // Posted rather than acted on directly: between focus leaving one rail item and
                // arriving at the next, neither has it and the rail would collapse and expand
                // again on every step down it.
                v.post(new Runnable() {
                    @Override
                    public void run() {
                        updateRailWidth();
                    }
                });
            }
        });

        navRail.addView(item);
    }

    /**
     * Expands the rail while it holds focus and collapses it otherwise, so the labels are readable
     * when they are being used and out of the way when they are not.
     */
    private void updateRailWidth() {
        int target = getResources().getDimensionPixelSize(navRail.hasFocus()
                ? R.dimen.rail_width_expanded
                : R.dimen.rail_width_collapsed);

        if (navRail.getLayoutParams().width == target) {
            return;
        }

        // ChangeBounds animates the rail and everything the resize pushes sideways. Framework
        // android.transition, no library, and it runs only on focus entering or leaving the rail.
        TransitionManager.beginDelayedTransition((ViewGroup) navRail.getParent(), new ChangeBounds());

        navRail.getLayoutParams().width = target;
        navRail.requestLayout();
    }

    // ------------------------------------------------------------------------------------------
    // Host band
    // ------------------------------------------------------------------------------------------

    /**
     * Rebuilds the host band from {@link BrowseState}.
     *
     * <p>Re-inflates only when the set of hosts has actually changed, and otherwise rebinds the
     * tiles already there. Poll results arrive about once a second per host and almost all of them
     * report the same hosts in the same order; re-inflating on each one would restart the focus
     * animation and drop the user's focus roughly once a second, which makes the band unusable
     * from a D-pad.
     */
    private void rebuildHostBand() {
        List<ComputerDetails> hosts = browseState.getHosts();

        if (!bandMatches(hosts)) {
            String focusedUuid = focusedHostUuid();

            hostBand.removeAllViews();
            for (ComputerDetails details : hosts) {
                hostBand.addView(createHostTile(details));
            }

            // Only needed on this path: rebinding leaves the views, and their focus, in place.
            if (focusedUuid != null) {
                View restored = findHostTile(focusedUuid);
                if (restored != null) {
                    restored.requestFocus();
                }
            }
        }

        for (int i = 0; i < hosts.size(); i++) {
            bindHostTile(hostBand.getChildAt(i), hosts.get(i));
        }
    }

    /** @return true if the band already holds exactly these hosts, in this order */
    private boolean bandMatches(List<ComputerDetails> hosts) {
        if (hostBand.getChildCount() != hosts.size()) {
            return false;
        }

        for (int i = 0; i < hosts.size(); i++) {
            if (!hosts.get(i).uuid.equalsIgnoreCase((String) hostBand.getChildAt(i).getTag())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Inflates an empty tile and attaches the handlers that do not depend on host state.
     *
     * <p>Both handlers resolve the host through the tile's tag rather than closing over a
     * {@link ComputerDetails}, because every poll delivers a fresh instance and the tile outlives
     * all of them.
     */
    private View createHostTile(ComputerDetails details) {
        View tile = LayoutInflater.from(this).inflate(R.layout.host_tile, hostBand, false);
        tile.setTag(details.uuid);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ComputerDetails current = hostForTile(v);
                if (current != null) {
                    onHostClicked(current);
                }
            }
        });

        // Not registerForContextMenu: a framework context menu renders as a popup anchored to the
        // gesture when the long press carried coordinates and as a centred dialog when it did not,
        // so a tile long-pressed with a mouse and one long-pressed with the d-pad produced two
        // different-looking menus - and neither matched the app grid's. Raising the menu ourselves
        // means one presentation whatever the input device. See MenuDialog.
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                ComputerDetails current = hostForTile(v);
                if (current == null) {
                    return false;
                }

                showHostMenu(current);
                return true;
            }
        });

        return tile;
    }

    /** Applies one host's current state to its tile: artwork, badge, spinner and selection. */
    private void bindHostTile(View tile, ComputerDetails details) {
        ImageView icon = tile.findViewById(R.id.grid_image);
        ImageView badge = tile.findViewById(R.id.grid_overlay);
        ProgressBar spinner = tile.findViewById(R.id.grid_spinner);
        TextView label = tile.findViewById(R.id.grid_text);

        boolean online = details.state == ComputerDetails.State.ONLINE;
        float alpha = getFloat(online ? R.dimen.tile_alpha_normal : R.dimen.tile_alpha_offline);

        icon.setAlpha(alpha);
        label.setAlpha(alpha);
        label.setText(details.name);

        // Only while the host's reachability is genuinely unknown. The badge takes the same spot,
        // so showing both at once would stack them.
        spinner.setVisibility(details.state == ComputerDetails.State.UNKNOWN
                ? View.VISIBLE : View.INVISIBLE);

        if (details.state == ComputerDetails.State.OFFLINE) {
            badge.setImageResource(R.drawable.ic_pc_offline);
            badge.setAlpha(getFloat(R.dimen.tile_alpha_offline));
            badge.setVisibility(View.VISIBLE);
        }
        else if (online && details.pairState != PairState.PAIRED) {
            badge.setImageResource(R.drawable.ic_lock);
            badge.setAlpha(getFloat(R.dimen.tile_alpha_normal));
            badge.setVisibility(View.VISIBLE);
        }
        else {
            badge.setVisibility(View.GONE);
        }

        // Selected is a resting state the tile's drawables read; it is what marks the host whose
        // apps are below, as distinct from the one focus happens to be on.
        tile.setSelected(details.uuid.equalsIgnoreCase(browseState.getSelectedUuid()));
    }

    /**
     * A host tile was activated: browse it if it can be browsed, pair it if it cannot yet, and
     * otherwise offer the menu, which is the only thing left to do with an offline host.
     */
    private void onHostClicked(ComputerDetails details) {
        if (details.state == ComputerDetails.State.UNKNOWN
                || details.state == ComputerDetails.State.OFFLINE) {
            showHostMenu(details);
        }
        else if (details.pairState != PairState.PAIRED) {
            doPair(details);
        }
        else {
            selectHost(details.uuid, false);
        }
    }

    private String focusedHostUuid() {
        for (int i = 0; i < hostBand.getChildCount(); i++) {
            View child = hostBand.getChildAt(i);
            if (child.hasFocus()) {
                return (String) child.getTag();
            }
        }
        return null;
    }

    private View findHostTile(String uuid) {
        for (int i = 0; i < hostBand.getChildCount(); i++) {
            View child = hostBand.getChildAt(i);
            if (uuid.equalsIgnoreCase((String) child.getTag())) {
                return child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // App grid
    // ------------------------------------------------------------------------------------------

    private void wireAppGrid() {
        appGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                AppObject app = (AppObject) appGridAdapter.getItem(position);
                ComputerDetails computer = browseState.getSelected();
                if (computer == null) {
                    return;
                }

                // Only open the menu if something is running, otherwise start it.
                // Tapping the app that is already running just resumes it, if the user
                // opted out of the confirmation.
                if (lastRunningAppId != 0) {
                    if (lastRunningAppId == app.app.getAppId() &&
                            PreferenceConfiguration.readPreferences(BrowseActivity.this).resumeWithoutConfirm) {
                        ServerHelper.doStart(BrowseActivity.this, app.app, computer, managerBinder);
                    } else {
                        showAppMenu(app, view);
                    }
                } else {
                    ServerHelper.doStart(BrowseActivity.this, app.app, computer, managerBinder);
                }
            }
        });

        // The grid's cells cannot take focus - GridView has no setItemsCanFocus - so the focus
        // lift that host tiles get from a StateListAnimator has to be driven from the selection
        // callback instead. The ring itself is the GridView's listSelector, which does follow
        // the grid's own focus state.
        appGrid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                liftTile(view);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                liftTile(null);
            }
        });

        // Focus leaving the grid entirely leaves its selection intact, so the lifted tile would
        // stay raised with no ring on it.
        appGrid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    liftTile(null);
                }
            }
        });

        // Handling the long press here, and claiming it by returning true, is what keeps
        // AbsListView from raising its own context menu - which it renders as a popup anchored to
        // the press when the press carried coordinates and as a dialog when it did not, so the
        // grid and the host band disagreed with each other and with themselves. See MenuDialog.
        appGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (appGridAdapter == null || browseState.getSelected() == null) {
                    return false;
                }

                showAppMenu((AppObject) appGridAdapter.getItem(position), view);
                return true;
            }
        });
    }

    /**
     * Raises the selected cell and lowers the one before it, matching what
     * {@code animator/tile_focus.xml} does for views that can hold focus themselves.
     *
     * @param view the newly selected cell, or null to simply lower whatever is raised
     */
    private void liftTile(View view) {
        if (liftedTile == view) {
            return;
        }

        if (liftedTile != null) {
            liftedTile.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(200).start();
        }

        if (view != null) {
            float scale = getFloat(R.dimen.tile_focus_scale);
            float lift = getResources().getDimensionPixelSize(R.dimen.tile_focus_lift);
            view.animate().scaleX(scale).scaleY(scale).translationZ(lift).setDuration(150).start();
        }

        liftedTile = view;
    }

    /**
     * Points the grid at a host, or at nothing. This is the user-driven entry point — a click on
     * the host band, or "View All Apps" from the host menu.
     *
     * @param showHidden include apps the user has hidden, which is the only thing "View All Apps"
     *                   changes about an already-selected host
     */
    private void selectHost(String uuid, boolean showHidden) {
        boolean sameHost = uuid != null && uuid.equalsIgnoreCase(browseState.getSelectedUuid());

        if (sameHost && showHidden == showHiddenApps && appGridAdapter != null) {
            return;
        }

        if (!sameHost && !browseState.select(uuid)) {
            // Not a host that can be browsed. The caller has already offered pairing or the menu.
            return;
        }

        showHiddenApps = showHidden;
        applySelection();
    }

    /**
     * Rebuilds everything tied to the selected host: its adapter, its cached box art loads and its
     * app list poller. None of that is valid for a different machine, so it is all torn down and
     * recreated rather than updated.
     *
     * <p>Called both from {@link #selectHost} and from a poll result that changed the selection on
     * its own — a host going offline underneath the user, or the first reachable host appearing.
     */
    private void applySelection() {
        stopApplistPoller();

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
            appGridAdapter = null;
        }

        appGrid.setAdapter(null);
        liftedTile = null;
        lastRawApplist = null;
        lastRunningAppId = 0;

        ComputerDetails computer = browseState.getSelected();
        if (computer != null) {
            getSharedPreferences(SELECTION_PREF_FILENAME, MODE_PRIVATE)
                    .edit()
                    .putString(SELECTION_PREF_KEY, computer.uuid)
                    .apply();

            loadHiddenAppIds(computer.uuid);

            // A launcher shortcut for a host the user has actually opened is worth keeping around.
            shortcutHelper.createAppViewShortcut(computer, true,
                    getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
            shortcutHelper.reportComputerShortcutUsed(computer);

            if (managerBinder != null) {
                try {
                    appGridAdapter = new AppGridAdapter(this,
                            computer, managerBinder.getUniqueId(), showHiddenApps);
                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);
                    appGrid.setAdapter(appGridAdapter);

                    populateAppGridWithCache(computer);
                    startApplistPoller(computer);
                } catch (Exception e) {
                    // Matches what AppView did on the same failure: the adapter's constructor
                    // touches the disk cache, and a screen with no grid is not usable.
                    e.printStackTrace();
                    appGridAdapter = null;
                }
            }
        }

        rebuild();
    }

    private void loadHiddenAppIds(String uuid) {
        hiddenAppIds.clear();

        if (uuid == null) {
            return;
        }

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuid, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }
    }

    /**
     * Applies the user's hidden-app selection to the grid.
     *
     * @param hideImmediately hide them now, rather than leaving them visible until the user leaves
     *                        the screen — which is what makes hiding several apps in a row usable
     */
    private void updateHiddenApps(boolean hideImmediately) {
        ComputerDetails computer = browseState.getSelected();
        if (computer == null || appGridAdapter == null) {
            return;
        }

        HashSet<String> hiddenAppIdStringSet = new HashSet<>();
        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(computer.uuid, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    /** Shows the last known app list from disk so the grid isn't empty while the host is queried. */
    private void populateAppGridWithCache(ComputerDetails computer) {
        try {
            lastRawApplist = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(getCacheDir(), "applist", computer.uuid));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: " + lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            blockingLoadSpinner = SpinnerDialog.displayDialog(this,
                    getResources().getString(R.string.applist_refresh_title),
                    getResources().getString(R.string.applist_refresh_msg), true);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rendering state
    // ------------------------------------------------------------------------------------------

    /** Redraws everything {@link BrowseState} decides: the host band, the labels and the grid. */
    private void rebuild() {
        rebuildHostBand();

        BrowseState.Content content = browseState.getContent();
        ComputerDetails selected = browseState.getSelected();
        String hostName = selected != null ? selected.name : "";

        boolean showGrid = content == BrowseState.Content.APPS
                && appGridAdapter != null
                && appGridAdapter.getCount() > 0;

        appGrid.setVisibility(showGrid ? View.VISIBLE : View.INVISIBLE);
        contentMessage.setVisibility(showGrid ? View.GONE : View.VISIBLE);

        appSectionLabel.setVisibility(selected != null ? View.VISIBLE : View.GONE);
        if (selected != null) {
            appSectionLabel.setText(getResources().getString(R.string.section_apps, hostName));
        }

        if (!showGrid) {
            contentMessage.setText(messageFor(content, hostName));
        }
    }

    private String messageFor(BrowseState.Content content, String hostName) {
        return switch (content) {
            case NO_HOSTS -> getResources().getString(R.string.no_pc_added);
            case NO_SELECTION -> getResources().getString(R.string.browse_no_selection);
            case HOST_PROBING -> getResources().getString(R.string.browse_host_probing, hostName);
            case HOST_OFFLINE -> getResources().getString(R.string.browse_host_offline, hostName);
            case HOST_UNPAIRED -> getResources().getString(R.string.browse_host_unpaired, hostName);
            // APPS with an empty grid: the host answered and had nothing to list.
            case APPS -> getResources().getString(R.string.browse_no_apps, hostName);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Service and polling
    // ------------------------------------------------------------------------------------------

    /**
     * Starts host polling. One listener serves the whole screen: it maintains the host band for
     * every host, and additionally drives the app grid for whichever one is selected. Before the
     * merge each activity installed its own, and the binder only holds one.
     */
    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder == null || runningPolling || !inForeground) {
            return;
        }

        freezeUpdates = false;
        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                if (freezeUpdates) {
                    return;
                }

                // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                if (details.pairState == PairState.PAIRED) {
                    shortcutHelper.createAppViewShortcutForOnlineHost(details);
                }

                final String rawAppList = details.rawAppList;
                final boolean isSelected = details.uuid.equalsIgnoreCase(browseState.getSelectedUuid());

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onHostUpdated(details, isSelected, rawAppList);
                    }
                });
            }
        });
        runningPolling = true;
    }

    /** Applies one poll result on the main thread. */
    private void onHostUpdated(ComputerDetails details, boolean wasSelected, String rawAppList) {
        boolean selectionChanged = browseState.update(details);

        if (selectionChanged) {
            // The host we were on became unusable, or a first usable host appeared. Either way the
            // grid now belongs to a different machine (or to none). BrowseState has already moved
            // the selection, so this applies it rather than asking for it again.
            applySelection();

            if (wasSelected) {
                Toast.makeText(this,
                        details.state == ComputerDetails.State.OFFLINE
                                ? getResources().getText(R.string.lost_connection)
                                : getResources().getText(R.string.scut_not_paired),
                        Toast.LENGTH_SHORT).show();

                if (details.state == ComputerDetails.State.ONLINE
                        && details.pairState != PairState.PAIRED) {
                    shortcutHelper.disableComputerShortcut(details,
                            getResources().getString(R.string.scut_not_paired));
                }
            }
            return;
        }

        if (!suspendGridUpdates && wasSelected && appGridAdapter != null) {
            if (rawAppList == null || rawAppList.equals(lastRawApplist)) {
                if (details.runningGameId != lastRunningAppId) {
                    lastRunningAppId = details.runningGameId;
                    updateUiWithServerinfo(details);
                }
            }
            else {
                lastRunningAppId = details.runningGameId;
                lastRawApplist = rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        }

        rebuild();
    }

    /**
     * Stops polling hosts.
     *
     * @param wait block until in-flight polls have finished, which is required before the process
     *             can safely tear down the service binding
     */
    private void stopComputerUpdates(boolean wait) {
        if (managerBinder == null || !runningPolling) {
            return;
        }

        freezeUpdates = true;
        managerBinder.stopPolling();

        if (wait) {
            managerBinder.waitForPollingStopped();
        }

        runningPolling = false;
    }

    /**
     * Starts the faster app-list poll for one host. Separate from the host poll above, which only
     * establishes reachability; this is what notices an app starting or stopping.
     */
    private void startApplistPoller(ComputerDetails computer) {
        if (managerBinder == null || !inForeground) {
            return;
        }

        poller = managerBinder.createAppListPoller(computer);
        poller.start();
    }

    private void stopApplistPoller() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /** {@inheritDoc} Re-inflates so the layout picks up the new configuration. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Only reinitialize views if completeOnCreate() was called before this callback. If it was
        // not, completeOnCreate() will handle initializing views with the config change accounted
        // for. This is not prone to races because both callbacks are invoked in the main thread.
        if (!completeOnCreateCalled) {
            return;
        }

        initializeViews();

        // initializeViews() rebuilt the grid's views but not its adapter, which survives the
        // configuration change and still holds the loaded box art.
        if (appGridAdapter != null) {
            appGridAdapter.updateLayoutForConfiguration(this);
            appGrid.setAdapter(appGridAdapter);
            rebuild();
        }
    }

    /** {@inheritDoc} Resumes polling and reports a decoder crash from the previous session. */
    @Override
    protected void onResume() {
        super.onResume();

        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

        ComputerDetails selected = browseState.getSelected();
        if (selected != null && poller == null) {
            startApplistPoller(selected);
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
        stopApplistPoller();

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    /** {@inheritDoc} Nothing is visible to update. */
    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
        SpinnerDialog.closeDialogs(this);
    }

    /** {@inheritDoc} Unbinds the computer manager. */
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------------------------------

    private ComputerDetails hostForTile(View tile) {
        String uuid = (String) tile.getTag();
        if (uuid == null) {
            return null;
        }

        for (ComputerDetails details : browseState.getHosts()) {
            if (uuid.equalsIgnoreCase(details.uuid)) {
                return details;
            }
        }

        return null;
    }

    /**
     * The menu for one host: pairing, the running session, and the two ways of undoing the host's
     * setup.
     *
     * <p>Which rows appear is {@link BrowseMenuLayout}'s decision, so it can be tested; the labels
     * and actions are here because they need a Context and a bound
     * {@link ComputerManagerService}. Same division as {@link GameMenu} and {@code GameMenuLayout}.
     */
    private void showHostMenu(final ComputerDetails details) {
        // Host polling would otherwise redraw the band under an open menu, which on a rebuild
        // destroys the tile the menu was raised from.
        stopComputerUpdates(false);

        String title = details.name + " - " + getString(switch (details.state) {
            case ONLINE -> R.string.pcview_menu_header_online;
            case OFFLINE -> R.string.pcview_menu_header_offline;
            case UNKNOWN -> R.string.pcview_menu_header_unknown;
        });

        // The only state worth a glance rather than a read. Offline is the one that explains why
        // most of the menu is missing.
        int icon = details.state == ComputerDetails.State.OFFLINE ? R.drawable.ic_pc_offline : 0;

        List<MenuDialog.Option> options = new ArrayList<>();

        for (BrowseMenuLayout.HostRow row : BrowseMenuLayout.hostRows(
                new BrowseMenuLayout.HostState(details.state == ComputerDetails.State.ONLINE,
                        details.pairState == PairState.PAIRED, details.runningGameId != 0))) {
            switch (row) {
                case PAIR -> options.add(new MenuDialog.Option(
                        getString(R.string.pcview_menu_pair_pc), () -> doPair(details)));

                case RESUME -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_resume), () -> resumeHostSession(details)));

                case QUIT -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_quit), () -> quitHostSession(details)));

                case APP_LIST -> options.add(new MenuDialog.Option(
                        getString(R.string.pcview_menu_app_list),
                        () -> selectHost(details.uuid, true)));

                case UNPAIR -> options.add(new MenuDialog.Option(
                        getString(R.string.pcview_menu_unpair_pc),
                        () -> UiHelper.displayUnpairConfirmationDialog(this, details,
                                () -> doUnpair(details), null)));

                case DELETE -> options.add(new MenuDialog.Option(
                        getString(R.string.pcview_menu_delete_pc), () -> deleteHost(details)));

                case DETAILS -> options.add(new MenuDialog.Option(
                        getString(R.string.pcview_menu_details),
                        () -> Dialog.displayDialog(this, getString(R.string.title_details),
                                details.toString(), false)));
            }
        }

        // Polling restarts however the menu goes away, a row selection included. startComputerUpdates
        // manages the foreground check itself: this fires again after onPause() on some paths, and
        // it will not actually poll until the activity is back.
        MenuDialog.show(this, title, icon, options, null, this::startComputerUpdates);
    }

    /** Resumes whatever the host is already running, without knowing which app that is. */
    private void resumeHostSession(ComputerDetails details) {
        if (managerBinder == null) {
            Toast.makeText(this, getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        ServerHelper.doStart(this, new NvApp("app", details.runningGameId, false), details, managerBinder);
    }

    private void quitHostSession(final ComputerDetails details) {
        if (managerBinder == null) {
            Toast.makeText(this, getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        UiHelper.displayQuitConfirmationDialog(this,
                () -> ServerHelper.doQuit(BrowseActivity.this, details,
                        new NvApp("app", 0, false), managerBinder, null), null);
    }

    private void deleteHost(final ComputerDetails details) {
        if (ActivityManager.isUserAMonkey()) {
            LimeLog.info("Ignoring delete PC request from monkey");
            return;
        }

        UiHelper.displayDeletePcConfirmationDialog(this, details, () -> {
            if (managerBinder == null) {
                Toast.makeText(BrowseActivity.this,
                        getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                return;
            }

            removeComputer(details);
        }, null);
    }

    /**
     * The menu for one app.
     *
     * @param cell the grid cell the menu was raised from, needed only for the box art already
     *             loaded into it — a shortcut has nowhere else to get an image from
     */
    private void showAppMenu(final AppObject app, View cell) {
        final ComputerDetails computer = browseState.getSelected();
        if (computer == null) {
            return;
        }

        final Bitmap boxArt = boxArtOf(cell);
        List<MenuDialog.Option> options = new ArrayList<>();

        for (BrowseMenuLayout.AppRow row : BrowseMenuLayout.appRows(
                new BrowseMenuLayout.AppState(lastRunningAppId != 0,
                        lastRunningAppId == app.app.getAppId(), app.isHidden, boxArt != null))) {
            switch (row) {
                // Resume is the same as start for us.
                case RESUME -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_resume),
                        () -> ServerHelper.doStart(this, app.app, computer, managerBinder)));

                case QUIT -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_quit), () -> quitApp(app, computer)));

                case QUIT_AND_START -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_quit_and_start),
                        () -> UiHelper.displayQuitConfirmationDialog(this,
                                () -> ServerHelper.doStart(BrowseActivity.this, app.app, computer,
                                        managerBinder), null)));

                case HIDE -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_hide_app), () -> setAppHidden(app, true)));

                case UNHIDE -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_show_app), () -> setAppHidden(app, false)));

                case DETAILS -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_details),
                        () -> Dialog.displayDialog(this, getString(R.string.title_details),
                                app.app.toString(), false)));

                case SHORTCUT -> options.add(new MenuDialog.Option(
                        getString(R.string.applist_menu_scut),
                        () -> createShortcut(app, computer, boxArt)));
            }
        }

        MenuDialog.show(this, app.app.getAppName(), options);
    }

    /**
     * The box art the cell has already loaded, or null if it has not loaded yet.
     *
     * <p>The drawable is only a {@link BitmapDrawable} once {@code AppGridAdapter} has put real
     * artwork there; before that it is the placeholder, which has no bitmap to pin.
     */
    private static Bitmap boxArtOf(View cell) {
        ImageView image = cell.findViewById(R.id.grid_image);

        if (image != null && image.getDrawable() instanceof BitmapDrawable drawable) {
            return drawable.getBitmap();
        }

        return null;
    }

    private void quitApp(final AppObject app, final ComputerDetails computer) {
        UiHelper.displayQuitConfirmationDialog(this, () -> {
            // The grid must not be rebuilt from a poll that is still reporting the app as
            // running; the callback below lifts this and polls once the quit has landed.
            suspendGridUpdates = true;

            ServerHelper.doQuit(BrowseActivity.this, computer, app.app, managerBinder, () -> {
                suspendGridUpdates = false;
                if (poller != null) {
                    poller.pollNow();
                }
            });
        }, null);
    }

    private void setAppHidden(AppObject app, boolean hidden) {
        if (hidden) {
            hiddenAppIds.add(app.app.getAppId());
        }
        else {
            hiddenAppIds.remove(app.app.getAppId());
        }

        updateHiddenApps(false);
    }

    private void createShortcut(AppObject app, ComputerDetails computer, Bitmap boxArt) {
        if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, boxArt)) {
            Toast.makeText(this, getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Host actions
    // ------------------------------------------------------------------------------------------

    /**
     * Runs the pairing exchange with a host and shows the PIN the user must type there.
     *
     * <p>Runs off the UI thread: pairing is a multi-step network exchange that waits on the user
     * entering the PIN on the host.
     */
    private void doPair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(BrowseActivity.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        final String pinStr = PairingManager.generatePinString();

                        // Spin the dialog off in a thread because it blocks
                        Dialog.displayDialog(BrowseActivity.this, getResources().getString(R.string.pair_pairing_title),
                                getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                getResources().getString(R.string.pair_pairing_help), false);

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just select the host without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(BrowseActivity.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        // Polling has to resume either way: on success it is what reports the new
                        // pair state, which is what makes the host selectable.
                        startComputerUpdates();
                    }
                });
            }
        }).start();
    }

    /**
     * Asks the host to forget this client, off the UI thread.
     *
     * <p>Not reachable before the browsing activities were merged: upstream declared the menu id
     * and handled it, but never added the menu item, and no label string for one existed.
     *
     * <p>Unlike pairing, this does not stop host polling while it runs. It is a single request,
     * and the poll result that reports NOT_PAIRED is what drives the screen back to the unpaired
     * state - {@link BrowseState} revokes the selection on it, so the grid empties on its own.
     */
    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean unpaired = false;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(BrowseActivity.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                            unpaired = true;
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                if (unpaired) {
                    // Cached reachability still says PAIRED, and the band and the app grid are
                    // drawn from it. Invalidating forces the pair state to be read again on the
                    // next poll rather than served from the cache, which is the same reason
                    // doPair() invalidates after a successful pairing.
                    managerBinder.invalidateStateForComputer(computer.uuid);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(BrowseActivity.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** Removes a host from the band, the database and any launcher shortcuts pointing at it. */
    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        // Disable or delete shortcuts referencing this PC
        shortcutHelper.disableComputerShortcut(details,
                getResources().getString(R.string.scut_deleted_pc));

        boolean wasSelected = details.uuid.equalsIgnoreCase(browseState.getSelectedUuid());
        browseState.remove(details.uuid);

        if (wasSelected) {
            selectHost(null, showHiddenApps);
        }
        else {
            rebuild();
        }
    }

    // ------------------------------------------------------------------------------------------
    // App list maintenance
    // ------------------------------------------------------------------------------------------

    /** Reflects a host state update: which app is currently running. */
    private void updateUiWithServerinfo(ComputerDetails details) {
        boolean updated = false;

        // Look through our current app list to tag the running app
        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

            // There can only be one or zero apps running.
            if (existingApp.isRunning &&
                    existingApp.app.getAppId() == details.runningGameId) {
                // This app was running and still is, so we're done now
                return;
            }
            else if (existingApp.app.getAppId() == details.runningGameId) {
                // This app wasn't running but now is
                existingApp.isRunning = true;
                updated = true;
            }
            else if (existingApp.isRunning) {
                // This app was running but now isn't
                existingApp.isRunning = false;
                updated = true;
            }
        }

        if (updated) {
            appGridAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Merges a freshly fetched app list into the grid, preserving the existing entries where they
     * still exist so that box art already loaded isn't discarded and refetched.
     */
    private void updateUiWithAppList(List<NvApp> appList) {
        ComputerDetails computer = browseState.getSelected();
        if (appGridAdapter == null || computer == null) {
            return;
        }

        boolean updated = false;

        // First handle app updates and additions
        for (NvApp app : appList) {
            boolean foundExistingApp = false;

            for (int i = 0; i < appGridAdapter.getCount(); i++) {
                AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                if (existingApp.app.getAppId() == app.getAppId()) {
                    // Found the app; update its properties
                    if (!existingApp.app.getAppName().equals(app.getAppName())) {
                        existingApp.app.setAppName(app.getAppName());
                        updated = true;
                    }

                    foundExistingApp = true;
                    break;
                }
            }

            if (!foundExistingApp) {
                // This app must be new
                appGridAdapter.addApp(new AppObject(app));

                // We could have a leftover shortcut from last time this PC was paired
                // or if this app was removed then added again. Enable those shortcuts
                // again if present.
                shortcutHelper.enableAppShortcut(computer, app);

                updated = true;
            }
        }

        // Next handle app removals
        int i = 0;
        while (i < appGridAdapter.getCount()) {
            boolean foundExistingApp = false;
            AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

            for (NvApp app : appList) {
                if (existingApp.app.getAppId() == app.getAppId()) {
                    foundExistingApp = true;
                    break;
                }
            }

            if (!foundExistingApp) {
                shortcutHelper.disableAppShortcut(computer, existingApp.app, "App removed from PC");
                appGridAdapter.removeApp(existingApp);
                updated = true;

                // Check this same index again because the item at i+1 is now at i after
                // the removal
                continue;
            }

            i++;
        }

        if (updated) {
            appGridAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Reads a float resource declared as {@code <item format="float" type="dimen">}.
     *
     * <p>{@code Resources.getFloat} is API 29 but hidden until API 34, so a TypedValue is the only
     * way to read one that works on the Shield at API 30.
     */
    private float getFloat(int resId) {
        TypedValue value = new TypedValue();
        getResources().getValue(resId, value, true);
        return value.getFloat();
    }
}
