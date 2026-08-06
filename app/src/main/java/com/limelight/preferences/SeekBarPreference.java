package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.DialogPreference;

// Based on a Stack Overflow example: http://stackoverflow.com/questions/1974193/slider-on-my-preferencescreen
//
// Ported from android.preference.DialogPreference to androidx.preference. The attribute
// parsing and persistence stay here; the dialog itself moved to
// SeekBarPreferenceDialogFragment, because androidx splits DialogPreference into a
// preference plus a PreferenceDialogFragmentCompat rather than having the preference build
// its own view. Attributes are still read straight off the AttributeSet by namespace URL,
// so no declare-styleable is needed.
public class SeekBarPreference extends DialogPreference
{
    private static final String ANDROID_SCHEMA_URL = "http://schemas.android.com/apk/res/android";
    private static final String SEEKBAR_SCHEMA_URL = "http://schemas.moonlight-stream.com/apk/res/seekbar";

    private final String suffix;
    private final int defaultValue;
    private final int maxValue;
    private final int minValue;
    private final int stepSize;
    private final int keyStepSize;
    private final int divisor;
    private int currentValue;

    // Android's SeekBar progress always starts at 0, so a negative minimum is represented
    // by offsetting progress by this amount. It is 0 whenever min >= 0, which leaves the
    // behaviour of every existing preference untouched.
    private final int progressOffset;

    public SeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        // NB: android:dialogMessage is parsed by androidx DialogPreference itself, so it
        // is read through the inherited getDialogMessage() rather than off the AttributeSet.

        // Get the suffix for the number displayed in the dialog
        int suffixId = attrs.getAttributeResourceValue(ANDROID_SCHEMA_URL, "text", 0);
        if (suffixId == 0) {
            suffix = attrs.getAttributeValue(ANDROID_SCHEMA_URL, "text");
        }
        else {
            suffix = context.getString(suffixId);
        }

        // Get default, min, and max seekbar values
        defaultValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "defaultValue", PreferenceConfiguration.getDefaultBitrate(context));
        maxValue = attrs.getAttributeIntValue(ANDROID_SCHEMA_URL, "max", 100);
        minValue = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "min", 1);
        stepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "step", 1);
        divisor = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "divisor", 1);
        keyStepSize = attrs.getAttributeIntValue(SEEKBAR_SCHEMA_URL, "keyStep", 0);
        progressOffset = Math.min(minValue, 0);
    }

    String getSuffix() { return suffix; }
    int getMaxValue() { return maxValue; }
    int getMinValue() { return minValue; }
    int getStepSize() { return stepSize; }
    int getKeyStepSize() { return keyStepSize; }
    int getDivisor() { return divisor; }
    int getProgressOffset() { return progressOffset; }

    @Override
    protected Object onGetDefaultValue(android.content.res.TypedArray a, int index) {
        return a.getInt(index, defaultValue);
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        if (defaultValue == null) {
            currentValue = shouldPersist() ? getPersistedInt(this.defaultValue) : 0;
        }
        else {
            currentValue = shouldPersist()
                    ? getPersistedInt((Integer) defaultValue)
                    : (Integer) defaultValue;
        }
    }

    public void setProgress(int progress) {
        this.currentValue = progress;
        if (shouldPersist()) {
            persistInt(progress);
        }
    }

    public int getProgress() {
        return currentValue;
    }

    /** Called by the dialog fragment when the user confirms a new value. */
    void commitProgress(int progress) {
        if (shouldPersist()) {
            currentValue = progress;
            persistInt(currentValue);
            callChangeListener(currentValue);
        }
    }
}
