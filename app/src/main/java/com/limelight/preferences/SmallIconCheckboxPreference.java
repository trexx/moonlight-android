package com.limelight.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.preference.CheckBoxPreference;
import android.util.AttributeSet;

/** Checkbox preference that keeps its icon at list-item size instead of the platform default. */
public class SmallIconCheckboxPreference extends CheckBoxPreference {
    /** {@inheritDoc} */
    public SmallIconCheckboxPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** {@inheritDoc} */
    public SmallIconCheckboxPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** {@inheritDoc} */
    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return PreferenceConfiguration.getDefaultSmallMode(getContext());
    }
}
