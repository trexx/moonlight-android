package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.text.InputType;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The surface the stream is decoded into, plus the input plumbing that has to live on the view.
 *
 * <p>Three things beyond a plain {@link SurfaceView}:
 * <ul>
 *   <li>Aspect-ratio-preserving measurement, either letterboxed or overscanned to fill the display
 *       ({@link #setFillDisplay(boolean)}).</li>
 *   <li>Key events intercepted before the IME sees them, so the host receives them intact -
 *       except while the soft keyboard is up, when the IME gets first refusal
 *       ({@link #setImeVisible(boolean)}).</li>
 *   <li>An {@link InputConnection} that turns soft keyboard text into host input - as real
 *       keystrokes wherever the character has a key, since that is all a game can see.</li>
 * </ul>
 */
public class StreamView extends SurfaceView {
    private double desiredAspectRatio;
    private boolean fillDisplay;
    private InputCallbacks inputCallbacks;
    // Cached rather than queried per key press: getRootWindowInsets() allocates, and every
    // gamepad button on the stream comes through onKeyPreIme().
    private boolean imeVisible;

    /** Sets the stream's aspect ratio, which drives measurement. */
    public void setDesiredAspectRatio(double aspectRatio) {
        this.desiredAspectRatio = aspectRatio;
    }

    /** @param fillDisplay overscan to fill the display and crop the surplus, instead of letterboxing */
    public void setFillDisplay(boolean fillDisplay) {
        this.fillDisplay = fillDisplay;
    }

    /**
     * Tracks soft keyboard visibility, which decides who owns key events. Pushed in from
     * {@link com.limelight.Game}'s window insets listener rather than queried here.
     */
    public void setImeVisible(boolean visible) {
        this.imeVisible = visible;
    }

    /** Sets the sink for key and text events this view intercepts. */
    public void setInputCallbacks(InputCallbacks callbacks) {
        this.inputCallbacks = callbacks;
    }

    /** {@inheritDoc} */
    public StreamView(Context context) {
        super(context);
    }

    /** {@inheritDoc} */
    public StreamView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** {@inheritDoc} */
    public StreamView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** {@inheritDoc} */
    public StreamView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sizes the surface to the stream's aspect ratio, letterboxing or overscanning per
     * {@link #setFillDisplay(boolean)}.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If no fixed aspect ratio has been provided, simply use the default onMeasure() behavior
        if (desiredAspectRatio == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Based on code from: https://www.buzzingandroid.com/2012/11/easy-measuring-of-custom-views-with-specific-aspect-ratio/
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int measuredHeight, measuredWidth;
        if (fillDisplay) {
            // Overscan instead of letterboxing: grow until both axes are covered and let
            // the surplus be cropped. Aspect ratio is still preserved.
            if (widthSize < heightSize * desiredAspectRatio) {
                measuredHeight = heightSize;
                measuredWidth = (int)(heightSize * desiredAspectRatio);
            } else {
                measuredWidth = widthSize;
                measuredHeight = (int)(widthSize / desiredAspectRatio);
            }
        }
        else if (widthSize > heightSize * desiredAspectRatio) {
            measuredHeight = heightSize;
            measuredWidth = (int)(measuredHeight * desiredAspectRatio);
        } else {
            measuredWidth = widthSize;
            measuredHeight = (int)(measuredWidth / desiredAspectRatio);
        }

        setMeasuredDimension(measuredWidth, measuredHeight);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Intercepts keys before the IME so they reach the host. Back is the exception: it has to
     * dismiss the soft keyboard when one is open.
     */
    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        // While the soft keyboard is up it owns the d-pad. Intercepting here would send the
        // navigation to the host and leave the keyboard itself unfocused, which is exactly what
        // used to happen. Whatever the IME declines still reaches the host afterwards through the
        // normal dispatch into Game's OnKeyListener, so nothing is lost by standing aside.
        if (imeVisible) {
            return super.onKeyPreIme(keyCode, event);
        }

        // This callbacks allows us to override dumb IME behavior like when
        // Samsung's default keyboard consumes Shift+Space.
        if (inputCallbacks != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (inputCallbacks.handleKeyDown(event)) {
                    return true;
                }
            }
            else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (inputCallbacks.handleKeyUp(event)) {
                    return true;
                }
            }
        }

        return super.onKeyPreIme(keyCode, event);
    }

    /** {@inheritDoc} Always true: this is what makes the IME target this view. */
    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a connection that forwards committed text and deletions to the host instead of
     * editing a local buffer.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Basic text editor flags - we don't need extract UI or an enter action
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (inputCallbacks != null && inputCallbacks.handleCommitText(text)) {
                    return true;
                }
                return super.commitText(text, newCursorPosition);
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (inputCallbacks != null && inputCallbacks.handleDeleteSurroundingText(beforeLength, afterLength)) {
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Deliberately not super: BaseInputConnection parks composing text in a scratch
                // Editable, and outside full-editor mode it flushes that buffer back into this
                // view as key events the moment composition ends. Since commitText above never
                // lets it clear the buffer, that flush retyped the last composed word on the host
                // - which is what backspace looked like it was doing, because ending a composition
                // is exactly what an IME does when you backspace out of one. Nothing may be left
                // in that Editable, so the previews never reach it.
                if (inputCallbacks != null && inputCallbacks.handleComposingText(text)) {
                    return true;
                }
                return super.setComposingText(text, newCursorPosition);
            }

            @Override
            public boolean finishComposingText() {
                // The composition is now final. Since setComposingText above kept it away from
                // BaseInputConnection, this is the only chance to send it for an IME that
                // finalises a word this way rather than by committing it.
                if (inputCallbacks != null && inputCallbacks.handleFinishComposingText()) {
                    return true;
                }
                return super.finishComposingText();
            }
        };
    }

    /** Sink for the input this view intercepts before Android's normal handling. */
    public interface InputCallbacks {
        boolean handleKeyUp(KeyEvent event);
        boolean handleKeyDown(KeyEvent event);
        boolean handleCommitText(CharSequence text);
        boolean handleDeleteSurroundingText(int beforeLength, int afterLength);
        boolean handleComposingText(CharSequence text);
        boolean handleFinishComposingText();
    }
}
