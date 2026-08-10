package com.limelight.ui;

import com.limelight.binding.input.GameInputDevice;

/**
 * Client-side actions a gesture or button combination can trigger during a stream.
 *
 * <p>Implemented by {@code Game}, and called from the input handlers when they recognise an
 * escape hatch: a fullscreen stream has no visible controls, so these are the only way to reach
 * the keyboard or the menu without disconnecting.
 */
public interface GameGestures {
    /** Shows or hides the soft keyboard over the stream. */
    void toggleKeyboard();

    /**
     * Opens the in-stream menu.
     *
     * @param device the controller that asked for it, so its own options can be offered, or null
     */
    void showGameMenu(GameInputDevice device);
}
