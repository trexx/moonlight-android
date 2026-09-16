package com.limelight.utils;

/**
 * Which dialog in a chain of replacements is the live one.
 *
 * <p>The in-game menu is a chain of {@link MenuDialog}s: each submenu is its own dialog, opened
 * from a row of the one above, and Back reopens the parent as yet another new dialog. Something
 * shown for the whole menu — the battery label — has to go away when the <em>chain</em> ends, not
 * when any one dialog does, and the framework's ordering makes that harder than it sounds:
 * {@code AlertController} runs a row's click listener <em>before</em> it dismisses the dialog, and
 * {@code onDismiss} is delivered as a posted message. So the child opens first and only then does
 * the parent's {@code onDismiss} arrive. Hiding on every dismiss hid the label the moment any
 * submenu opened.
 *
 * <p>Each dialog takes a token when it opens; its {@code onDismiss} asks whether it is still the
 * latest. Only the last dialog in the chain is, so only it ends the chain.
 *
 * <p>Main thread only — the menu opens and dismisses there and nowhere else.
 */
public final class DialogChain {
    private int generation;

    /** Records a dialog opening. @return the token its {@code onDismiss} must present. */
    public int opened() {
        return ++generation;
    }

    /**
     * @return true if no dialog has opened since {@code token} was issued, which is to say this
     *         dismissal ends the chain
     */
    public boolean closes(int token) {
        return token == generation;
    }
}
