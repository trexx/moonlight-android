package com.limelight.preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Guards the shape of the settings tree, which is spread over one navigation screen and six
 * sub-screens in {@code res/xml/}.
 *
 * <p>These are resource-shape assertions rather than behaviour ones: nothing here constructs an
 * Android Preference, because the framework preference classes cannot load on a JVM. What they
 * cover is what splitting one preferences.xml into seven files can silently break, and what no
 * other test can see - a key that disappears takes a user's setting with it, and a dependency that
 * lands in a different file from its target crashes the screen that holds it.
 */
class SettingsTreeTest {

    /**
     * Every preference key the tree is expected to contain.
     *
     * <p>Spelled out rather than derived, so that a key vanishing in a refactor fails here instead
     * of agreeing with itself. Each of these is read by {@link PreferenceConfiguration}; a rename
     * on one side without the other silently resets that setting to its default.
     */
    private static final Set<String> EXPECTED_KEYS = new TreeSet<>(Arrays.asList(
            // Video & Display
            "list_resolution",
            "list_fps",
            "seekbar_bitrate_kbps",
            "video_format",
            "checkbox_enable_hdr",
            "frame_pacing",
            "list_video_scale_mode",
            "checkbox_unlock_fps",
            "checkbox_reduce_refresh_rate",
            "checkbox_enforce_display_mode",
            // Audio
            "list_audio_config",
            "checkbox_enable_aaudio",
            "checkbox_continuous_audio",
            "checkbox_host_audio",
            // Controllers
            "seekbar_deadzone",
            "checkbox_multi_controller",
            "checkbox_flip_face_buttons",
            "checkbox_gamepad_motion_sensors",
            "checkbox_mouse_emulation",
            "analog_scrolling",
            "checkbox_gamepad_touchpad_as_mouse",
            "checkbox_usb_driver",
            "checkbox_usb_bind_all",
            "checkbox_wired_pad_audio",
            "list_guide_button_led",
            // Mouse & Keyboard
            "list_mouse_mode",
            "checkbox_mouse_nav_buttons",
            "checkbox_absolute_mouse_mode",
            // Host & Connection
            "checkbox_enable_sops",
            "checkbox_resume_without_confirm",
            "list_encryption",
            "checkbox_send_real_client_id",
            // Advanced & Diagnostics
            "checkbox_enable_perf_overlay",
            "checkbox_enable_post_stream_toast",
            "checkbox_disable_warnings",
            "checkbox_enable_intra_refresh",
            "checkbox_full_range"));

    private static final String ROOT_FILE = "preferences.xml";

    /**
     * Locates {@code app/src/main/res/xml}.
     *
     * <p>Gradle runs unit tests with the module directory as the working directory, but that is not
     * guaranteed of every runner, so walk up until the path resolves rather than depending on it.
     */
    private static File xmlDir() {
        for (File dir = new File("").getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
            File candidate = new File(dir, "app/src/main/res/xml");
            if (candidate.isDirectory()) {
                return candidate;
            }
            // Already inside the app module.
            candidate = new File(dir, "src/main/res/xml");
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return fail("could not locate app/src/main/res/xml from " + new File("").getAbsolutePath());
    }

    private static Document parse(File file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // The preference XML has no DTD and no entities; keep the parser from fetching either.
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(file);
        } catch (Exception e) {
            return fail("could not parse " + file, e);
        }
    }

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static String androidAttr(Element element, String name) {
        String value = element.getAttributeNS(ANDROID_NS, name);
        return value.isEmpty() ? null : value;
    }

    /** Every element in the document, in document order. */
    private static List<Element> elements(Document document) {
        List<Element> result = new ArrayList<>();
        NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            result.add((Element) all.item(i));
        }
        return result;
    }

    /** The sub-screen files, keyed by file name. A category key is not a preference key. */
    private static Map<String, Set<String>> keysByScreenFile() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (File file : screenFiles()) {
            Set<String> keys = new LinkedHashSet<>();
            for (Element element : elements(parse(file))) {
                String key = androidAttr(element, "key");
                if (key != null && !key.startsWith("category_") && !key.startsWith("screen_")) {
                    keys.add(key);
                }
            }
            result.put(file.getName(), keys);
        }
        return result;
    }

    /** The preferences_*.xml sub-screens, excluding the navigation root. */
    private static List<File> screenFiles() {
        File[] files = xmlDir().listFiles(
                (dir, name) -> name.startsWith("preferences_") && name.endsWith(".xml"));
        if (files == null) {
            return fail("no preference XML found in " + xmlDir());
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(File::compareTo);
        return sorted;
    }

    @Nested
    @DisplayName("preference keys")
    class Keys {

        @Test
        @DisplayName("the sub-screens hold exactly the expected set")
        void subScreensHoldExpectedKeys() {
            Set<String> found = new TreeSet<>();
            keysByScreenFile().values().forEach(found::addAll);

            assertEquals(EXPECTED_KEYS, found,
                    "a preference key was added, removed or renamed; a user's stored setting is "
                            + "keyed on this string, so update PreferenceConfiguration too");
        }

        @Test
        @DisplayName("no key appears on two screens")
        void noKeyIsDuplicated() {
            Set<String> seen = new HashSet<>();
            Map<String, Set<String>> byFile = keysByScreenFile();

            for (Map.Entry<String, Set<String>> entry : byFile.entrySet()) {
                for (String key : entry.getValue()) {
                    assertTrue(seen.add(key),
                            key + " appears on more than one screen (" + entry.getKey()
                                    + "); two Preferences sharing a key fight over one stored value");
                }
            }
        }

        @Test
        @DisplayName("the root screen carries navigation rows only")
        void rootHoldsNoSettings() {
            for (Element element : elements(parse(new File(xmlDir(), ROOT_FILE)))) {
                String key = androidAttr(element, "key");
                if (key == null) {
                    continue;
                }

                assertTrue(key.startsWith("screen_"),
                        ROOT_FILE + " should only hold navigation rows, but carries " + key
                                + "; a setting there is unreachable from SettingsScreen's filters");
            }
        }
    }

    @Nested
    @DisplayName("preference dependencies")
    class Dependencies {

        /**
         * A dependency resolves through PreferenceManager.findPreference(), which searches only the
         * screen currently set on the manager, and Preference.registerDependency() throws
         * IllegalStateException when it comes back empty. Splitting a pair across two files
         * therefore crashes the screen holding the dependent as it binds - at runtime, on a device,
         * with nothing at build time to catch it.
         */
        @Test
        @DisplayName("each dependency target sits on the same screen as its dependent")
        void dependenciesAreWithinOneScreen() {
            for (File file : screenFiles()) {
                Document document = parse(file);
                Set<String> keysHere = new HashSet<>();
                for (Element element : elements(document)) {
                    String key = androidAttr(element, "key");
                    if (key != null) {
                        keysHere.add(key);
                    }
                }

                for (Element element : elements(document)) {
                    String dependency = androidAttr(element, "dependency");
                    if (dependency == null) {
                        continue;
                    }

                    assertTrue(keysHere.contains(dependency),
                            androidAttr(element, "key") + " in " + file.getName()
                                    + " depends on " + dependency + ", which is on another screen; "
                                    + "this throws IllegalStateException when that screen binds");
                }
            }
        }

        @Test
        @DisplayName("the dependency edges are the ones we expect")
        void dependencyEdgesArePinned() {
            Set<String> edges = new TreeSet<>();
            for (File file : screenFiles()) {
                for (Element element : elements(parse(file))) {
                    String dependency = androidAttr(element, "dependency");
                    if (dependency != null) {
                        edges.add(androidAttr(element, "key") + " -> " + dependency);
                    }
                }
            }

            assertEquals(new TreeSet<>(Arrays.asList(
                            "analog_scrolling -> checkbox_mouse_emulation",
                            "checkbox_usb_bind_all -> checkbox_usb_driver",
                            "checkbox_wired_pad_audio -> checkbox_usb_bind_all",
                            "list_guide_button_led -> checkbox_usb_driver")),
                    edges,
                    "the dependency graph changed; check the new edge is within one screen and "
                            + "that StreamSettings still removes these leaf-first");
        }
    }

    @Nested
    @DisplayName("navigation")
    class Navigation {

        /** The screen_* keys the root screen offers. */
        private Set<String> rootRowKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (Element element : elements(parse(new File(xmlDir(), ROOT_FILE)))) {
                String key = androidAttr(element, "key");
                if (key != null && key.startsWith("screen_")) {
                    keys.add(key);
                }
            }
            return keys;
        }

        @Test
        @DisplayName("every navigation row names a screen the enum knows")
        void rowsMatchTheEnum() {
            assertEquals(
                    new TreeSet<>(Arrays.asList("screen_video", "screen_audio", "screen_controllers",
                            "screen_mouse_keyboard", "screen_host", "screen_advanced")),
                    new TreeSet<>(rootRowKeys()),
                    "the root's rows and StreamSettings.SettingsScreen must agree, or a row opens "
                            + "nothing when tapped");
        }

        @Test
        @DisplayName("there are as many sub-screen files as navigation rows")
        void everyScreenIsReachable() {
            assertEquals(rootRowKeys().size(), screenFiles().size(),
                    "a preferences_*.xml with no row on the root screen is unreachable: "
                            + screenFiles());
        }

        @Test
        @DisplayName("a nested PreferenceScreen is never used to navigate")
        void noNestedPreferenceScreens() {
            for (File file : screenFiles()) {
                Document document = parse(file);
                NodeList nested = document.getElementsByTagName("PreferenceScreen");

                // The document element is itself a PreferenceScreen; any further one would be
                // presented as a Dialog, which never gets the activity's overscan padding.
                assertEquals(1, nested.getLength(),
                        file.getName() + " nests a PreferenceScreen, which the framework shows in "
                                + "a Dialog rather than as a screen");
            }
        }

        @Test
        @DisplayName("the root screen nests none either")
        void rootNestsNoPreferenceScreens() {
            Document document = parse(new File(xmlDir(), ROOT_FILE));
            assertEquals(1, document.getElementsByTagName("PreferenceScreen").getLength(),
                    ROOT_FILE + " must use plain <Preference> rows: a nested PreferenceScreen "
                            + "opens a Dialog before onPreferenceTreeClick is ever reached");
        }
    }

    @Nested
    @DisplayName("declared defaults")
    class Defaults {

        /**
         * The XML default and the code-side default are read in different situations - the XML one
         * when the settings screen writes an untouched preference out, the code one when
         * readPreferences() reads a preference that was never written. They have to agree, which
         * the file header of every preferences_*.xml asserts but nothing enforced.
         */
        @Test
        @DisplayName("every preference declares one, except the computed bitrate")
        void everyPreferenceDeclaresADefault() {
            List<String> missing = new ArrayList<>();

            for (File file : screenFiles()) {
                for (Element element : elements(parse(file))) {
                    String key = androidAttr(element, "key");
                    if (key == null || key.startsWith("category_")) {
                        continue;
                    }
                    // The bitrate's default is not a constant: getDefaultBitrate() derives it from
                    // the chosen resolution and frame rate.
                    if (key.equals("seekbar_bitrate_kbps")) {
                        continue;
                    }

                    if (androidAttr(element, "defaultValue") == null) {
                        missing.add(key + " (" + file.getName() + ")");
                    }
                }
            }

            assertTrue(missing.isEmpty(),
                    "these declare no android:defaultValue, so the XML and the code-side default "
                            + "cannot be checked against each other: " + missing);
        }
    }

    @Test
    @DisplayName("the XML files are all well-formed")
    void allFilesParse() throws IOException {
        for (File file : screenFiles()) {
            assertFalse(elements(parse(file)).isEmpty(), file + " parsed to nothing");
        }
        assertFalse(elements(parse(new File(xmlDir(), ROOT_FILE))).isEmpty(), "root parsed to nothing");
    }
}
