package com.limelight.preferences;

import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.os.Build;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.util.DisplayMetrics;
import android.util.Range;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.utils.Dialog;
import com.limelight.utils.UiHelper;

import java.util.Arrays;

/**
 * The settings screen.
 *
 * <p>The tree is a root of navigation rows over six sub-screens, one {@code preferences_*.xml}
 * each, rather than a single list of every preference. That is a navigation choice for a ten-foot
 * UI - a d-pad reaches any group in one press instead of scrolling past everything else - but it
 * also bounds the work each screen does: the decoder and display capability queries in
 * {@link SettingsFragment#filterVideoScreen()} used to run on every entry to settings and now run
 * only when the screen whose contents depend on them is opened.
 *
 * <p>Most of the logic here is making the options reflect the device: resolution and frame rate
 * lists are filtered to what the display and decoders can actually do, and options whose hardware
 * support is missing are removed rather than shown and ignored.
 *
 * <p>Some changes require rebuilding a screen, which is why the fragment is recreated rather than
 * updated in place after those.
 */
public class StreamSettings extends Activity {
    private int previousDisplayPixelCount;

    /**
     * A screen in the settings tree.
     *
     * <p>{@code key} is what the matching navigation row in {@code preferences.xml} carries, and is
     * how a click is resolved back to a screen. {@code ROOT} has none, because nothing navigates to
     * it by key.
     */
    enum SettingsScreen {
        ROOT(null, R.xml.preferences, R.string.title_settings),
        VIDEO("screen_video", R.xml.preferences_video, R.string.screen_video),
        AUDIO("screen_audio", R.xml.preferences_audio, R.string.screen_audio),
        CONTROLLERS("screen_controllers", R.xml.preferences_controllers, R.string.screen_controllers),
        MOUSE_KEYBOARD("screen_mouse_keyboard", R.xml.preferences_mouse_keyboard, R.string.screen_mouse_keyboard),
        HOST("screen_host", R.xml.preferences_host, R.string.screen_host),
        ADVANCED("screen_advanced", R.xml.preferences_advanced, R.string.screen_advanced);

        final String key;
        final int xmlResId;
        final int titleResId;

        SettingsScreen(String key, int xmlResId, int titleResId) {
            this.key = key;
            this.xmlResId = xmlResId;
            this.titleResId = titleResId;
        }

        /** The screen a navigation row opens, or null if this key is not one of them. */
        static SettingsScreen forKey(String key) {
            for (SettingsScreen screen : values()) {
                if (screen.key != null && screen.key.equals(key)) {
                    return screen;
                }
            }
            return null;
        }
    }

    /**
     * Returns the display the given activity is attached to.
     *
     * Replaces the deprecated getWindowManager().getDefaultDisplay(). Activity.getDisplay()
     * can return null when the activity isn't attached to a display, which the old API never
     * did, so fall back to the default display to preserve the previous non-null contract.
     */
    static Display getActivityDisplay(Activity activity) {
        Display display = activity.getDisplay();
        if (display == null) {
            display = activity.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        }
        return display;
    }

    private Display getActivityDisplay() {
        return getActivityDisplay(this);
    }

    /**
     * Rebuilds the tree from its root, discarding any sub-screen the user had open.
     *
     * <p>Called when the settings are first shown and when the display underneath them changes,
     * both of which invalidate the device-dependent filtering on every screen.
     */
    void reloadSettings() {
        Display.Mode mode = getActivityDisplay().getMode();
        previousDisplayPixelCount = mode.getPhysicalWidth() * mode.getPhysicalHeight();

        getFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        showScreen(SettingsScreen.ROOT, false);
    }

    /**
     * Shows a screen, optionally leaving the one it replaces on the back stack.
     *
     * <p>Navigating into a sub-screen keeps the root, so Back returns to it. Rebuilding a screen
     * over itself does not, so a preference that changes what its own screen may offer can take
     * effect without stacking a second copy behind it - see the unlock-FPS listener.
     */
    void showScreen(SettingsScreen screen, boolean addToBackStack) {
        FragmentTransaction transaction = getFragmentManager().beginTransaction()
                .replace(R.id.stream_settings, SettingsFragment.forScreen(screen));
        if (addToBackStack) {
            transaction.addToBackStack(null);
        }
        transaction.commitAllowingStateLoss();

        setScreenTitle(screen);
    }

    /** AppTheme sets windowNoTitle, so the heading is a view of the layout's own. */
    private void setScreenTitle(SettingsScreen screen) {
        TextView title = findViewById(R.id.settings_title);
        if (title != null) {
            title.setText(screen.titleResId);
        }
    }

    /** {@inheritDoc} Builds the preference tree, filtered to the device's real capabilities. */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_stream_settings);

        UiHelper.notifyNewRootView(this);
    }

    /** {@inheritDoc} */
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        reloadSettings();
    }

    /** {@inheritDoc} */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        Display.Mode mode = getActivityDisplay().getMode();

        // If the display's physical pixel count has changed, we consider that it's a new display
        // and we should reload our settings (which include display-dependent values).
        //
        // NB: We aren't using displayId here because that stays the same (DEFAULT_DISPLAY) when
        // switching between screens on a foldable device.
        if (mode.getPhysicalWidth() * mode.getPhysicalHeight() != previousDisplayPixelCount) {
            reloadSettings();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Back leaves a sub-screen for the root before it leaves the activity.
     *
     * <p>The manifest sets {@code enableOnBackInvokedCallback="false"} for this activity so this
     * one path serves both supported boxes. The predictive-back callback that would otherwise be
     * needed exists only from API 33, and the Shield is API 30, so honouring it would mean two
     * behaviours to keep in step for no gain on a box driven by a remote's Back button.
     */
    @Override
    public void onBackPressed() {
        if (getFragmentManager().getBackStackEntryCount() > 0) {
            getFragmentManager().popBackStack();
            setScreenTitle(SettingsScreen.ROOT);
            return;
        }

        finish();
    }

    /** Builds one screen of the preference tree and filters it against the device's capabilities. */
    public static class SettingsFragment extends PreferenceFragment {
        private static final String SCREEN_ARG = "screen";

        private int nativeResolutionStartIndex = Integer.MAX_VALUE;
        private boolean nativeFramerateShown = false;

        static SettingsFragment forScreen(SettingsScreen screen) {
            SettingsFragment fragment = new SettingsFragment();
            Bundle args = new Bundle();
            args.putString(SCREEN_ARG, screen.name());
            fragment.setArguments(args);
            return fragment;
        }

        private SettingsScreen getScreen() {
            Bundle args = getArguments();
            if (args == null) {
                return SettingsScreen.ROOT;
            }
            return SettingsScreen.valueOf(args.getString(SCREEN_ARG, SettingsScreen.ROOT.name()));
        }

        private void setValue(String preferenceKey, String value) {
            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            pref.setValue(value);
        }

        /**
         * Removes a preference from whichever group holds it, and reports whether it was there.
         *
         * <p>Framework Preference has no getParent(). Looking the parent up by category key instead
         * tied each removal to a category name, which stopped removing anything - silently - as
         * soon as a preference moved between categories. Searching for the actual parent keeps the
         * caller honest about the only thing it really knows, which is the key.
         */
        private boolean removePreference(String key) {
            Preference pref = findPreference(key);
            return pref != null && removeFrom(getPreferenceScreen(), pref);
        }

        private static boolean removeFrom(PreferenceGroup group, Preference target) {
            if (group.removePreference(target)) {
                return true;
            }

            for (int i = 0; i < group.getPreferenceCount(); i++) {
                if (group.getPreference(i) instanceof PreferenceGroup child && removeFrom(child, target)) {
                    return true;
                }
            }

            return false;
        }

        private void appendPreferenceEntry(ListPreference pref, String newEntryName, String newEntryValue) {
            CharSequence[] newEntries = Arrays.copyOf(pref.getEntries(), pref.getEntries().length + 1);
            CharSequence[] newValues = Arrays.copyOf(pref.getEntryValues(), pref.getEntryValues().length + 1);

            // Add the new option
            newEntries[newEntries.length - 1] = newEntryName;
            newValues[newValues.length - 1] = newEntryValue;

            pref.setEntries(newEntries);
            pref.setEntryValues(newValues);
        }

        private void addNativeResolutionEntry(int nativeWidth, int nativeHeight, boolean insetsRemoved, boolean portrait) {
            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING);

            String newName;

            if (insetsRemoved) {
                newName = getResources().getString(R.string.resolution_prefix_native_fullscreen);
            }
            else {
                newName = getResources().getString(R.string.resolution_prefix_native);
            }

            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                if (portrait) {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_portrait);
                }
                else {
                    newName += " " + getResources().getString(R.string.resolution_prefix_native_landscape);
                }
            }

            newName += " ("+nativeWidth+"x"+nativeHeight+")";

            String newValue = nativeWidth+"x"+nativeHeight;

            // Check if the native resolution is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (newValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    return;
                }
            }

            if (pref.getEntryValues().length < nativeResolutionStartIndex) {
                nativeResolutionStartIndex = pref.getEntryValues().length;
            }
            appendPreferenceEntry(pref, newName, newValue);
        }

        private void addNativeResolutionEntries(int nativeWidth, int nativeHeight, boolean insetsRemoved) {
            if (PreferenceConfiguration.isSquarishScreen(nativeWidth, nativeHeight)) {
                addNativeResolutionEntry(nativeHeight, nativeWidth, insetsRemoved, true);
            }
            addNativeResolutionEntry(nativeWidth, nativeHeight, insetsRemoved, false);
        }

        private void addNativeFrameRateEntry(float framerate) {
            int frameRateRounded = Math.round(framerate);
            if (frameRateRounded == 0) {
                return;
            }

            ListPreference pref = (ListPreference) findPreference(PreferenceConfiguration.FPS_PREF_STRING);
            String fpsValue = Integer.toString(frameRateRounded);
            String fpsName = getResources().getString(R.string.resolution_prefix_native) +
                    " (" + fpsValue + " " + getResources().getString(R.string.fps_suffix_fps) + ")";

            // Check if the native frame rate is already present
            for (CharSequence value : pref.getEntryValues()) {
                if (fpsValue.equals(value.toString())) {
                    // It is present in the default list, so don't add it again
                    nativeFramerateShown = false;
                    return;
                }
            }

            appendPreferenceEntry(pref, fpsName, fpsValue);
            nativeFramerateShown = true;
        }

        private void removeValue(String preferenceKey, String value, Runnable onMatched) {
            int matchingCount = 0;

            ListPreference pref = (ListPreference) findPreference(preferenceKey);

            // Count the number of matching entries we'll be removing
            for (CharSequence seq : pref.getEntryValues()) {
                if (seq.toString().equalsIgnoreCase(value)) {
                    matchingCount++;
                }
            }

            // Create the new arrays
            CharSequence[] entries = new CharSequence[pref.getEntries().length-matchingCount];
            CharSequence[] entryValues = new CharSequence[pref.getEntryValues().length-matchingCount];
            int outIndex = 0;
            for (int i = 0; i < pref.getEntryValues().length; i++) {
                if (pref.getEntryValues()[i].toString().equalsIgnoreCase(value)) {
                    // Skip matching values
                    continue;
                }

                entries[outIndex] = pref.getEntries()[i];
                entryValues[outIndex] = pref.getEntryValues()[i];
                outIndex++;
            }

            if (pref.getValue().equalsIgnoreCase(value)) {
                onMatched.run();
            }

            // Update the preference with the new list
            pref.setEntries(entries);
            pref.setEntryValues(entryValues);
        }

        private void resetBitrateToDefault(SharedPreferences prefs, String res, String fps) {
            if (res == null) {
                res = prefs.getString(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);
            }
            if (fps == null) {
                fps = prefs.getString(PreferenceConfiguration.FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS);
            }

            prefs.edit()
                    .putInt(PreferenceConfiguration.BITRATE_PREF_STRING,
                            PreferenceConfiguration.getDefaultBitrate(res, fps))
                    .apply();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            UiHelper.applyStatusBarPadding(view);
            return view;
        }

        /**
         * {@inheritDoc}
         *
         * <p>The navigation rows on the root screen are plain Preferences, whose {@code onClick()}
         * does nothing, so the click arrives here for us to act on. A nested PreferenceScreen would
         * instead have shown its children in a Dialog before this was ever reached, and that dialog
         * is built straight from the theme - it never receives the overscan padding
         * {@code activity_stream_settings.xml} applies, which makes it wrong on a television.
         */
        @Override
        public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
            SettingsScreen screen = SettingsScreen.forKey(preference.getKey());
            if (screen != null) {
                ((StreamSettings) getActivity()).showScreen(screen, true);
                return true;
            }

            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            SettingsScreen screen = getScreen();
            addPreferencesFromResource(screen.xmlResId);

            // Each screen filters only what it shows. Keeping these apart is what stops the decoder
            // capability queries in filterVideoScreen() from running when someone opens Audio.
            switch (screen) {
                case VIDEO -> filterVideoScreen();
                case CONTROLLERS -> filterControllerScreen();
                case MOUSE_KEYBOARD -> filterMouseKeyboardScreen();
                case HOST -> filterHostScreen();
                case ROOT, AUDIO, ADVANCED -> {
                    // Nothing on these depends on the device.
                }
            }
        }

        private void filterHostScreen() {
            // Warn that encrypting video will be done in software on this device. The setting
            // stays available - a user who wants it can have it and pay for it - so this appends
            // to the summary rather than removing or disabling the preference.
            if (!MoonBridge.hasFastAes()) {
                Preference encryptionPref = findPreference(PreferenceConfiguration.ENCRYPTION_PREF_STRING);
                if (encryptionPref != null) {
                    encryptionPref.setSummary(getString(R.string.summary_encryption)
                            + getString(R.string.summary_encryption_no_aes_warning));
                }
            }
        }

        private void filterMouseKeyboardScreen() {
            // Hide remote desktop mouse mode on NVIDIA SHIELD devices
            // (which support raw mouse input in pointer capture mode)
            if (getActivity().getPackageManager().hasSystemFeature("com.nvidia.feature.shield")) {
                removePreference("checkbox_absolute_mouse_mode");
            }
        }

        private void filterControllerScreen() {
            // Hide gamepad motion sensor option when running on OSes before Android 12.
            // Support for motion, LED, battery, and other extensions were introduced in S.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                removePreference("checkbox_gamepad_motion_sensors");
            }

            // Hide USB driver options on devices without USB host support. Removed leaf-first, so
            // nothing is briefly left depending on a preference that has already gone; the now
            // empty category goes last, or it would take its children's headings with it.
            if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
                removePreference("list_guide_button_led");
                removePreference("checkbox_wired_pad_audio");
                removePreference("checkbox_usb_bind_all");
                removePreference("checkbox_usb_driver");
                removePreference("category_controllers_usb");
            }
        }

        /**
         * Filters the resolution, frame rate and HDR options down to what this device can do.
         *
         * <p>This is the expensive screen: it enumerates the display's modes and queries the AVC
         * and HEVC decoders' capabilities. Nothing else in the tree needs any of it, which is why
         * it is reached only by opening Video &amp; Display.
         */
        private void filterVideoScreen() {
            Display display = getActivityDisplay(getActivity());
            float maxSupportedFps = display.getRefreshRate();

            // Hide non-supported resolution/FPS combinations
            int maxSupportedResW = 0;

            // Add a native resolution with any insets included for users that don't want content
            // behind the notch of their display
            boolean hasInsets = false;
            DisplayCutout cutout = display.getCutout();

            if (cutout != null) {
                int widthInsets = cutout.getSafeInsetLeft() + cutout.getSafeInsetRight();
                int heightInsets = cutout.getSafeInsetBottom() + cutout.getSafeInsetTop();

                if (widthInsets != 0 || heightInsets != 0) {
                    DisplayMetrics metrics = new DisplayMetrics();
                    display.getRealMetrics(metrics);

                    int width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                    int height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);

                    addNativeResolutionEntries(width, height, false);
                    hasInsets = true;
                }
            }

            // Always allow resolutions that are smaller or equal to the active
            // display resolution because decoders can report total non-sense to us.
            // For example, a p201 device reports:
            // AVC Decoder: OMX.amlogic.avc.decoder.awesome
            // HEVC Decoder: OMX.amlogic.hevc.decoder.awesome
            // AVC supported width range: 64 - 384
            // HEVC supported width range: 64 - 544
            for (Display.Mode candidate : display.getSupportedModes()) {
                // Some devices report their dimensions in the portrait orientation
                // where height > width. Normalize these to the conventional width > height
                // arrangement before we process them.

                int width = Math.max(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
                int height = Math.min(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());

                // Some TVs report strange values here, so let's avoid native resolutions on a TV
                // unless they report greater than 4K resolutions.
                if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
                        (width > 3840 || height > 2160)) {
                    addNativeResolutionEntries(width, height, hasInsets);
                }

                if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                    maxSupportedResW = 3840;
                }
                else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                    maxSupportedResW = 2560;
                }
                else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                    maxSupportedResW = 1920;
                }

                if (candidate.getRefreshRate() > maxSupportedFps) {
                    maxSupportedFps = candidate.getRefreshRate();
                }
            }

            // This must be called to do runtime initialization before calling functions that evaluate
            // decoder lists.
            MediaCodecHelper.initialize(getContext(), GlPreferences.readPreferences(getContext()).glRenderer);

            MediaCodecInfo avcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", -1);
            MediaCodecInfo hevcDecoder = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);

            if (avcDecoder != null) {
                Range<Integer> avcWidthRange = avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();

                LimeLog.info("AVC supported width range: "+avcWidthRange.getLower()+" - "+avcWidthRange.getUpper());

                // If 720p is not reported as supported, ignore all results from this API
                if (avcWidthRange.contains(1280)) {
                    if (avcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if (avcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }
                    else if (maxSupportedResW < 1280) {
                        maxSupportedResW = 1280;
                    }
                }
            }

            if (hevcDecoder != null) {
                Range<Integer> hevcWidthRange = hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();

                LimeLog.info("HEVC supported width range: "+hevcWidthRange.getLower()+" - "+hevcWidthRange.getUpper());

                // If 720p is not reported as supported, ignore all results from this API
                if (hevcWidthRange.contains(1280)) {
                    if (hevcWidthRange.contains(3840) && maxSupportedResW < 3840) {
                        maxSupportedResW = 3840;
                    }
                    else if (hevcWidthRange.contains(1920) && maxSupportedResW < 1920) {
                        maxSupportedResW = 1920;
                    }
                    else if (maxSupportedResW < 1280) {
                        maxSupportedResW = 1280;
                    }
                }
            }

            LimeLog.info("Maximum resolution slot: "+maxSupportedResW);

            if (maxSupportedResW != 0) {
                if (maxSupportedResW < 3840) {
                    // 4K is unsupported
                    removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_4K, new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P);
                            resetBitrateToDefault(prefs, null, null);
                        }
                    });
                }
                if (maxSupportedResW < 2560) {
                    // 1440p is unsupported
                    removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1440P, new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P);
                            resetBitrateToDefault(prefs, null, null);
                        }
                    });
                }
                if (maxSupportedResW < 1920) {
                    // 1080p is unsupported
                    removeValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_1080P, new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.RESOLUTION_PREF_STRING, PreferenceConfiguration.RES_720P);
                            resetBitrateToDefault(prefs, null, null);
                        }
                    });
                }
                // Never remove 720p
            }

            if (!PreferenceConfiguration.readPreferences(this.getActivity()).unlockFps) {
                // We give some extra room in case the FPS is rounded down
                if (maxSupportedFps < 118) {
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "120", new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.FPS_PREF_STRING, "90");
                            resetBitrateToDefault(prefs, null, null);
                        }
                    });
                }
                if (maxSupportedFps < 88) {
                    // 1080p is unsupported
                    removeValue(PreferenceConfiguration.FPS_PREF_STRING, "90", new Runnable() {
                        @Override
                        public void run() {
                            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                            setValue(PreferenceConfiguration.FPS_PREF_STRING, "60");
                            resetBitrateToDefault(prefs, null, null);
                        }
                    });
                }
                // Never remove 30 FPS or 60 FPS
            }
            addNativeFrameRateEntry(maxSupportedFps);

            // Android L introduces the drop duplicate behavior of releaseOutputBuffer()
            // that the unlock FPS option relies on to not massively increase latency.
            findPreference(PreferenceConfiguration.UNLOCK_FPS_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    // HACK: We need to let the preference change succeed before reinitializing to ensure
                    // it's reflected in the new layout.
                    final Handler h = new Handler(Looper.getMainLooper());
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // Ensure the activity is still open when this timeout expires
                            StreamSettings settingsActivity = (StreamSettings) SettingsFragment.this.getActivity();
                            if (settingsActivity != null) {
                                // Rebuild this screen over itself rather than the whole tree: the
                                // frame rate list is all the setting changes, and it is here.
                                settingsActivity.showScreen(SettingsScreen.VIDEO, false);
                            }
                        }
                    }, 500);

                    // Allow the original preference change to take place
                    return true;
                }
            });

            {
                Display.HdrCapabilities hdrCaps = display.getHdrCapabilities();

                // We must now ensure our display is compatible with HDR10
                boolean foundHdr10 = false;
                for (int hdrType : hdrCaps.getSupportedHdrTypes()) {
                    if (hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                        foundHdr10 = true;
                        break;
                    }
                }

                if (!foundHdr10) {
                    LimeLog.info("Excluding HDR toggle based on display capabilities");
                    removePreference("checkbox_enable_hdr");
                }
            }

            // Add a listener to the FPS and resolution preference
            // so the bitrate can be auto-adjusted
            findPreference(PreferenceConfiguration.RESOLUTION_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                    String valueStr = (String) newValue;

                    // Detect if this value is the native resolution option
                    CharSequence[] values = ((ListPreference)preference).getEntryValues();
                    boolean isNativeRes = true;
                    for (int i = 0; i < values.length; i++) {
                        // Look for a match prior to the start of the native resolution entries
                        if (valueStr.equals(values[i].toString()) && i < nativeResolutionStartIndex) {
                            isNativeRes = false;
                            break;
                        }
                    }

                    // If this is native resolution, show the warning dialog
                    if (isNativeRes) {
                        Dialog.displayDialog(getActivity(),
                                getResources().getString(R.string.title_native_res_dialog),
                                getResources().getString(R.string.text_native_res_dialog),
                                false);
                    }

                    // Write the new bitrate value
                    resetBitrateToDefault(prefs, valueStr, null);

                    // Allow the original preference change to take place
                    return true;
                }
            });
            findPreference(PreferenceConfiguration.FPS_PREF_STRING).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(SettingsFragment.this.getActivity());
                    String valueStr = (String) newValue;

                    // If this is native frame rate, show the warning dialog
                    CharSequence[] values = ((ListPreference)preference).getEntryValues();
                    if (nativeFramerateShown && values[values.length - 1].toString().equals(newValue.toString())) {
                        Dialog.displayDialog(getActivity(),
                                getResources().getString(R.string.title_native_fps_dialog),
                                getResources().getString(R.string.text_native_res_dialog),
                                false);
                    }

                    // Write the new bitrate value
                    resetBitrateToDefault(prefs, null, valueStr);

                    // Allow the original preference change to take place
                    return true;
                }
            });
        }
    }
}
