package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.grid.AppObject;
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
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
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
 * <p>Pairing, waking and removing hosts, and starting, quitting and hiding apps, are all driven
 * from the two context menus, which is why so much of this class is menu and dialog handling
 * rather than view code.
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

    // Host context menu.
    private final static int PAIR_ID = 1;
    private final static int DELETE_ID = 2;
    private final static int HOST_RESUME_ID = 3;
    private final static int HOST_QUIT_ID = 4;
    private final static int HOST_DETAILS_ID = 5;
    private final static int FULL_APP_LIST_ID = 6;
    private final static int TEST_NETWORK_ID = 7;
    private final static int MANAGEMENT_PAGE_ID = 8;

    // App context menu. A separate range so onContextItemSelected can dispatch on the id alone
    // and never confuse a host action for an app one.
    private final static int APP_START_OR_RESUME_ID = 20;
    private final static int APP_QUIT_ID = 21;
    private final static int APP_START_WITH_QUIT_ID = 22;
    private final static int APP_DETAILS_ID = 23;
    private final static int APP_HIDE_ID = 24;
    private final static int APP_CREATE_SHORTCUT_ID = 25;

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

    /**
     * The host whose context menu is open. Host tiles are not adapter items, so a context menu
     * raised from one carries no {@code AdapterContextMenuInfo} to identify it with.
     */
    private ComputerDetails contextMenuHost;

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
     * Fills the rail. Three destinations, inflated rather than declared in the layout because each
     * needs its own icon and label and {@code <include>} can supply neither.
     */
    private void buildRail() {
        addRailItem(R.drawable.ic_computer, R.string.section_hosts, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                focusHostBand();
            }
        });
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
     * <p>The click handler resolves the host through the tile's tag rather than closing over a
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
                    onHostClicked(current, v);
                }
            }
        });

        registerForContextMenu(tile);

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
    private void onHostClicked(ComputerDetails details, View tile) {
        if (details.state == ComputerDetails.State.UNKNOWN
                || details.state == ComputerDetails.State.OFFLINE) {
            openContextMenu(tile);
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

    /** Moves focus to the selected host, or the first one, and scrolls it into view. */
    private void focusHostBand() {
        View target = browseState.getSelectedUuid() != null
                ? findHostTile(browseState.getSelectedUuid())
                : (hostBand.getChildCount() > 0 ? hostBand.getChildAt(0) : null);

        if (target != null) {
            // requestFocus alone scrolls the band: taking focus walks up the parent chain, and
            // the HorizontalScrollView brings its focused descendant on screen itself.
            target.requestFocus();
        }
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

                // Only open the context menu if something is running, otherwise start it.
                // Tapping the app that is already running just resumes it, if the user
                // opted out of the confirmation.
                if (lastRunningAppId != 0) {
                    if (lastRunningAppId == app.app.getAppId() &&
                            PreferenceConfiguration.readPreferences(BrowseActivity.this).resumeWithoutConfirm) {
                        ServerHelper.doStart(BrowseActivity.this, app.app, computer, managerBinder);
                    } else {
                        openContextMenu(view);
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

        registerForContextMenu(appGrid);
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
                            PreferenceConfiguration.readPreferences(this),
                            computer, managerBinder.getUniqueId(), showHiddenApps);
                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);
                    appGrid.setAdapter(appGridAdapter);
                    applyGridCellSize();

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

    /**
     * Sets the grid's column width from the small-icon preference.
     *
     * <p>Not a resource qualifier: small icon mode is something the user chooses, not something
     * the device is. The two widths themselves are resources, so a television still gets bigger
     * cells at either setting.
     */
    private void applyGridCellSize() {
        boolean small = PreferenceConfiguration.readPreferences(this).smallIconMode;
        appGrid.setColumnWidth(getResources().getDimensionPixelSize(
                small ? R.dimen.app_tile_width_small : R.dimen.app_tile_width_large));
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
            appGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));
            appGrid.setAdapter(appGridAdapter);
            applyGridCellSize();
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
    // Context menus
    // ------------------------------------------------------------------------------------------

    /** {@inheritDoc} Builds the host menu or the app menu, depending on what was long-pressed. */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        if (v == appGrid) {
            contextMenuHost = null;
            buildAppContextMenu(menu, (AdapterContextMenuInfo) menuInfo);
        }
        else {
            // Host tiles are plain views, so there is no menuInfo to identify them by.
            contextMenuHost = hostForTile(v);
            if (contextMenuHost != null) {
                buildHostContextMenu(menu, contextMenuHost);
            }
        }
    }

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

    private void buildHostContextMenu(ContextMenu menu, ComputerDetails details) {
        // Host polling would otherwise redraw the band under an open menu, which on a rebuild
        // destroys the view the menu is anchored to.
        stopComputerUpdates(false);

        menu.clearHeader();
        String headerTitle = details.name + " - ";
        switch (details.state) {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }
        menu.setHeaderTitle(headerTitle);

        if (details.state != ComputerDetails.State.OFFLINE &&
            details.state != ComputerDetails.State.UNKNOWN &&
            details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID, 1, getResources().getString(R.string.pcview_menu_pair_pc));
        }
        else {
            if (details.runningGameId != 0) {
                menu.add(Menu.NONE, HOST_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, HOST_QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }

        // Sunshine serves its web UI one port above the HTTP port
        menu.add(Menu.NONE, MANAGEMENT_PAGE_ID, 5, getResources().getString(R.string.pcview_menu_management_page));

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, HOST_DETAILS_ID, 7, getResources().getString(R.string.pcview_menu_details));
    }

    private void buildAppContextMenu(ContextMenu menu, AdapterContextMenuInfo info) {
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId != 0) {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, APP_START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, APP_QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                menu.add(Menu.NONE, APP_START_WITH_QUIT_ID, 1, getResources().getString(R.string.applist_menu_quit_and_start));
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, APP_HIDE_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, APP_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        // Only offer a shortcut once there is box art to put on it.
        ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
        if (appImageView != null) {
            BitmapDrawable drawable = (BitmapDrawable) appImageView.getDrawable();
            if (drawable != null && drawable.getBitmap() != null) {
                menu.add(Menu.NONE, APP_CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onContextMenuClosed(Menu menu) {
        contextMenuHost = null;

        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actually start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    /** {@inheritDoc} Dispatches both menus; the id ranges keep them apart. */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (item.getItemId() >= APP_START_OR_RESUME_ID) {
            return onAppContextItemSelected(item);
        }
        return onHostContextItemSelected(item);
    }

    private boolean onHostContextItemSelected(MenuItem item) {
        final ComputerDetails details = contextMenuHost;
        if (details == null) {
            return super.onContextItemSelected(item);
        }

        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(BrowseActivity.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                selectHost(details.uuid, true);
                return true;

            case HOST_RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }
                ServerHelper.doStart(this, new NvApp("app", details.runningGameId, false), details, managerBinder);
                return true;

            case HOST_QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(BrowseActivity.this, details,
                                new NvApp("app", 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case HOST_DETAILS_ID:
                Dialog.displayDialog(this, getResources().getString(R.string.title_details), details.toString(), false);
                return true;

            case MANAGEMENT_PAGE_ID:
                if (details.activeAddress != null) {
                    String url = "https://" + details.activeAddress.address() + ":" +
                            (details.activeAddress.port() + 1);
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, getResources().getString(R.string.error_no_browser), Toast.LENGTH_LONG).show();
                    }
                }
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(this);
                return true;

            default:
                return super.onContextItemSelected(item);
        }
    }

    private boolean onAppContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerDetails computer = browseState.getSelected();
        if (info == null || appGridAdapter == null || computer == null) {
            return super.onContextItemSelected(item);
        }

        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);

        switch (item.getItemId()) {
            case APP_START_WITH_QUIT_ID:
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doStart(BrowseActivity.this, app.app, computer, managerBinder);
                    }
                }, null);
                return true;

            case APP_START_OR_RESUME_ID:
                // Resume is the same as start for us
                ServerHelper.doStart(this, app.app, computer, managerBinder);
                return true;

            case APP_QUIT_ID:
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(BrowseActivity.this, computer,
                                app.app, managerBinder, new Runnable() {
                            @Override
                            public void run() {
                                // Trigger a poll immediately
                                suspendGridUpdates = false;
                                if (poller != null) {
                                    poller.pollNow();
                                }
                            }
                        });
                    }
                }, null);
                return true;

            case APP_DETAILS_ID:
                Dialog.displayDialog(this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;

            case APP_HIDE_ID:
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                }
                else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;

            case APP_CREATE_SHORTCUT_ID:
                ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;

            default:
                return super.onContextItemSelected(item);
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
