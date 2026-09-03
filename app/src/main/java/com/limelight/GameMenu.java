package com.limelight;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;

import com.limelight.binding.audio.PadAudioSink;
import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.driver.GipController;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Provide options for ongoing Game Stream.
 * <p>
 * Shown on back action in game activity.
 */
public class GameMenu {

    private static final long TEST_GAME_FOCUS_DELAY = 10;
    private static final long KEY_UP_DELAY = 25;

    /**
     * One row of the in-stream menu.
     *
     * @param withGameFocus run the action only once the game Activity has focus back, for actions
     *                      that send input to the host
     */
    public record MenuOption(String label, boolean withGameFocus, Runnable runnable) {

        public MenuOption(String label, Runnable runnable) {
            this(label, false, runnable);
        }
    }

    private final Game game;
    private final NvConnection conn;
    private final GameInputDevice device;

    /** Building the menu shows it immediately; there is no separate show call. */
    public GameMenu(Game game, NvConnection conn, GameInputDevice device) {
        this.game = game;
        this.conn = conn;
        this.device = device;

        showMenu();
    }

    private String getString(int id) {
        return game.getResources().getString(id);
    }

    private static byte getModifier(short key) {
        switch (key) {
            case KeyboardTranslator.VK_LSHIFT:
                return KeyboardPacket.MODIFIER_SHIFT;
            case KeyboardTranslator.VK_LCONTROL:
                return KeyboardPacket.MODIFIER_CTRL;
            case KeyboardTranslator.VK_LMENU:
                return KeyboardPacket.MODIFIER_ALT;
            case KeyboardTranslator.VK_LWIN:
                return KeyboardPacket.MODIFIER_META;

            default:
                return 0;
        }
    }

    /**
     * Presses each key in order, accumulating modifiers as it goes, then releases them in reverse
     * after a short delay. The keys are raw Windows virtual key codes; the wire prefix is applied
     * here so these packets are encoded exactly like the ones the physical keyboard path sends.
     */
    private void sendKeys(short[] keys) {
        final byte[] modifier = {(byte) 0};

        for (short key : keys) {
            conn.sendKeyboardInput(KeyboardTranslator.toWireKeycode(key), KeyboardPacket.KEY_DOWN, modifier[0], (byte) 0);

            // Apply the modifier of the pressed key, e.g. CTRL first issues a CTRL event (without
            // modifier) and then sends the following keys with the CTRL modifier applied
            modifier[0] |= getModifier(key);
        }

        new Handler(Looper.getMainLooper()).postDelayed((() -> {

            for (int pos = keys.length - 1; pos >= 0; pos--) {
                short key = keys[pos];

                // Remove the keys modifier before releasing the key
                modifier[0] &= ~getModifier(key);

                conn.sendKeyboardInput(KeyboardTranslator.toWireKeycode(key), KeyboardPacket.KEY_UP, modifier[0], (byte) 0);
            }
        }), KEY_UP_DELAY);
    }

    private void runWithGameFocus(Runnable runnable) {
        // Ensure that the Game activity is still active (not finished)
        if (game.isFinishing()) {
            return;
        }
        // Check if the game window has focus again, if not try again after delay
        if (!game.hasWindowFocus()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }
        // Game Activity has focus, run runnable
        runnable.run();
    }

    private void run(MenuOption option) {
        if (option.runnable() == null) {
            return;
        }

        if (option.withGameFocus()) {
            runWithGameFocus(option.runnable());
        } else {
            option.runnable().run();
        }
    }

    private void showMenuDialog(String title, MenuOption[] options) {
        showMenuDialog(title, options, null);
    }

    /**
     * Shows one level of the menu.
     *
     * <p>{@code onBack} reopens the level above, and is what hardware Back runs. Each level is its
     * own AlertDialog with no relationship to the one that opened it, so without this a submenu's
     * Back dropped straight to the stream - further out than the user asked to go. The root passes
     * null, where dismissing really does mean returning to the game.
     */
    private void showMenuDialog(String title, MenuOption[] options, Runnable onBack) {
        AlertDialog.Builder builder = new AlertDialog.Builder(game);
        builder.setTitle(title);

        // A row layout of our own rather than android.R.layout.simple_list_item_1, which is sized
        // for a phone in the hand. This menu is only ever driven by a controller from across a
        // room. See game_menu_item.xml.
        final ArrayAdapter<String> actions =
                new ArrayAdapter<>(game, R.layout.game_menu_item);

        for (MenuOption option : options) {
            actions.add(option.label());
        }

        // Dispatch on the row index, not on the label. The adapter is filled from options in
        // order, so which indexes options directly. Matching by label instead made two rows that
        // happen to share text both run the first one's action - which the Back rows and the
        // repeated pad-audio state words below would otherwise do.
        builder.setAdapter(actions, (dialog, which) -> run(options[which]));

        // Fires on hardware Back and on a touch outside, but not on a row selection, which
        // dismisses rather than cancels.
        if (onBack != null) {
            builder.setOnCancelListener(dialog -> onBack.run());
        }

        builder.show();
    }

    /**
     * Lists the paired pads with their current audio state, so one can be toggled mid-game.
     *
     * <p>Each entry says what it is now rather than what selecting it would do, and a pad that
     * cannot be enabled because the two-pad limit is reached says so on the row. The cap is a
     * bandwidth budget on a link shared with controller input, so it is worth showing rather
     * than letting a selection quietly do nothing.
     */
    private void showPadAudioMenu() {
        List<GipController> controllers = game.getGipControllers();
        PadAudioSink sink = game.getPadAudioSink();
        List<MenuOption> options = new ArrayList<>();

        int number = 1;
        for (GipController controller : controllers) {
            boolean enabled = sink.isEnabled(controller);

            /*
             * A pad left streaming by a killed process stutters until its cable is pulled, and
             * nothing sent over GIP or USB clears it - the attempts are tabulated in AUDIO.md.
             * The driver can see the condition before a note is played (the sub-device answers
             * metadata without ever announcing), so the row says what to do about it rather than
             * leaving the stutter to be discovered by ear and blamed on the stream.
             */
            boolean stale = controller.audioNeedsReplug();
            String state;

            if (enabled) {
                state = getString(stale ? R.string.game_menu_pad_audio_on_stale
                                        : R.string.game_menu_pad_audio_on);
            }
            else if (!sink.isSupportedBy(controller)) {
                /*
                 * No audio sub-device has announced. That is almost always an empty headphone
                 * jack rather than a pad without one: [MS-GIPUSB] 1.2 makes sub-devices hot
                 * swappable, so the 3.5 mm audio device appears when a headset is plugged in and
                 * not before. The two cases are indistinguishable from here - both are simply an
                 * absent sub-device - so the wording names the one the user can act on.
                 *
                 * Distinct from the two-pad cap below, and worth saying so rather than blaming
                 * the limit.
                 */
                state = getString(R.string.game_menu_pad_audio_unsupported);
            }
            else if (sink.canEnableMore()) {
                state = getString(stale ? R.string.game_menu_pad_audio_off_stale
                                        : R.string.game_menu_pad_audio_off);
            }
            else {
                state = getString(R.string.game_menu_pad_audio_unavailable);
            }

            String label = game.getResources().getString(
                    R.string.game_menu_pad_audio_entry, number++, state);

            options.add(new MenuOption(label, () -> game.togglePadAudio(controller)));
        }

        // Only worth offering once a pad is actually taking audio - there is nothing to set the
        // volume of otherwise, and the level applies to whichever pads are on rather than to one.
        if (sink.hasTargets()) {
            options.add(new MenuOption(game.getResources().getString(
                    R.string.game_menu_pad_audio_volume, sink.getVolume()),
                    () -> showPadAudioVolumeMenu()));
        }

        options.add(new MenuOption(getString(R.string.game_menu_back), this::showControllerMenu));

        showMenuDialog(getString(R.string.game_menu_pad_audio),
                options.toArray(new MenuOption[0]), this::showControllerMenu);
    }

    /**
     * Fixed volume steps rather than a slider.
     *
     * <p>This menu is driven by a d-pad from across a room, where a slider means holding a
     * direction and guessing when to stop. Steps are one press each and land on the same value
     * every time, which also makes "put it back where it was" possible.
     *
     * <p>Volume has to exist somewhere in the UI because pad audio bypasses AudioTrack and AAudio
     * entirely, so the TV remote's volume keys do not reach it — see {@link PadAudioSink}.
     */
    private void showPadAudioVolumeMenu() {
        PadAudioSink sink = game.getPadAudioSink();
        int current = sink.getVolume();
        List<MenuOption> options = new ArrayList<>();

        for (int level : new int[]{100, 80, 60, 40, 20}) {
            String label = game.getResources().getString(
                    level == current ? R.string.game_menu_pad_audio_volume_current
                                     : R.string.game_menu_pad_audio_volume_entry, level);

            options.add(new MenuOption(label, () -> game.setPadAudioVolume(level)));
        }

        options.add(new MenuOption(getString(R.string.game_menu_pad_audio_volume_mute),
                () -> game.setPadAudioVolume(0)));
        options.add(new MenuOption(getString(R.string.game_menu_back), this::showPadAudioMenu));

        showMenuDialog(getString(R.string.game_menu_pad_audio_volume_title),
                options.toArray(new MenuOption[0]), this::showPadAudioMenu);
    }

    private void showSpecialKeysMenu() {
        showMenuDialog(getString(R.string.game_menu_send_keys), new MenuOption[]{
                new MenuOption(getString(R.string.game_menu_send_keys_esc),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE})),
                new MenuOption(getString(R.string.game_menu_send_keys_f11),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_F11})),
                new MenuOption(getString(R.string.game_menu_send_keys_alt_enter),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN})),
                new MenuOption(getString(R.string.game_menu_send_keys_alt_f4),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4})),
                new MenuOption(getString(R.string.game_menu_send_keys_ctrl_v),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V})),
                new MenuOption(getString(R.string.game_menu_send_keys_ctrl_shift_esc),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_ESCAPE})),
                new MenuOption(getString(R.string.game_menu_send_keys_win),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN})),
                new MenuOption(getString(R.string.game_menu_send_keys_win_d),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D})),
                new MenuOption(getString(R.string.game_menu_send_keys_win_g),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G})),
                new MenuOption(getString(R.string.game_menu_send_keys_win_shift_left),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_LEFT})),
                new MenuOption(getString(R.string.game_menu_send_keys_shift_tab),
                        () -> sendKeys(new short[]{KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB})),
                new MenuOption(getString(R.string.game_menu_back), this::showMenu),
        }, this::showMenu);
    }

    /**
     * The session facts {@link GameMenuLayout} decides the menu's shape from.
     *
     * <p>Read afresh each time a level is built rather than cached: a pad can be plugged in, or an
     * adapter claimed, while the menu is open.
     */
    private GameMenuLayout.State layoutState() {
        return new GameMenuLayout.State(
                device != null,
                game.hasXboxWirelessDongle(),
                !game.getGipControllers().isEmpty(),
                game.getPadAudioSink().isFormatSupported());
    }

    /**
     * Builds one level from the rows {@link GameMenuLayout} chose.
     *
     * <p>The mapping is here rather than in GameMenuLayout because every arm of it needs a Context
     * for its label or a Game for its action.
     */
    private MenuOption[] build(List<GameMenuLayout.Row> rows) {
        List<MenuOption> options = new ArrayList<>();

        for (GameMenuLayout.Row row : rows) {
            switch (row) {
                case KEYBOARD -> options.add(new MenuOption(
                        getString(R.string.game_menu_toggle_keyboard), true,
                        () -> game.toggleKeyboard()));
                case SEND_KEYS -> options.add(new MenuOption(
                        getString(R.string.game_menu_send_keys), () -> showSpecialKeysMenu()));
                case CONTROLLERS -> options.add(new MenuOption(
                        getString(R.string.game_menu_controllers), () -> showControllerMenu()));
                case PERF_OVERLAY -> options.add(new MenuOption(
                        getString(R.string.game_menu_toggle_performance_overlay),
                        () -> game.togglePerformanceOverlay()));
                case DISCONNECT -> options.add(new MenuOption(
                        getString(R.string.game_menu_disconnect), () -> game.disconnect()));
                case CANCEL -> options.add(new MenuOption(
                        getString(R.string.game_menu_cancel), null));

                // Supplied by the device itself, because what it offers - the mouse emulation
                // toggle, whose label says which way it will go - is the pad's own state.
                case MOUSE_EMULATION -> options.addAll(device.getGameMenuOptions());

                // Stands in for the adapter's physical pairing button, which is dead on some units.
                case PAIR_XBOX -> options.add(new MenuOption(
                        getString(R.string.game_menu_pair_xbox_controller),
                        () -> game.startDonglePairing()));

                // Not gated on the adapter: a cabled pad has a headphone jack too, and checking for
                // the adapter hid this entirely when the only pad was on a cable.
                case PAD_AUDIO -> options.add(new MenuOption(
                        getString(R.string.game_menu_pad_audio), () -> showPadAudioMenu()));

                case BACK -> options.add(new MenuOption(
                        getString(R.string.game_menu_back), () -> showMenu()));
            }
        }

        return options.toArray(new MenuOption[0]);
    }

    /**
     * The controller options, which are three of the four things this fork added to the menu.
     *
     * <p>They are a level down rather than at the top because the menu is shown mid-game, where
     * every row is between the user and Disconnect. The row that opens this is not offered at all
     * when none of its contents applies - see {@link GameMenuLayout#hasControllerOptions}.
     */
    private void showControllerMenu() {
        showMenuDialog(getString(R.string.game_menu_controllers),
                build(GameMenuLayout.controllerRows(layoutState())), this::showMenu);
    }

    private void showMenu() {
        showMenuDialog(getString(R.string.game_menu_title),
                build(GameMenuLayout.rootRows(layoutState())));
    }
}
