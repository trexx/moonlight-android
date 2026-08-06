package com.limelight.preferences;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceDialogFragmentCompat;

import java.util.Locale;

/**
 * The dialog half of {@link SeekBarPreference}.
 *
 * androidx.preference splits DialogPreference into the preference and a dialog fragment, so
 * what used to be onCreateDialogView()/onBindDialogView()/the positive-button override lives
 * here. The stepping, negative-minimum offset and divisor formatting are carried over
 * unchanged - notably Math.floorDiv() for the step rounding, which is what keeps the
 * gamepad deadzone preference (min -20) correct.
 */
public class SeekBarPreferenceDialogFragment extends PreferenceDialogFragmentCompat {
    private SeekBar seekBar;
    private TextView valueText;

    public static SeekBarPreferenceDialogFragment newInstance(String key) {
        SeekBarPreferenceDialogFragment fragment = new SeekBarPreferenceDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        fragment.setArguments(args);
        return fragment;
    }

    private SeekBarPreference getSeekBarPreference() {
        return (SeekBarPreference) getPreference();
    }

    @Override
    protected View onCreateDialogView(Context context) {
        final SeekBarPreference pref = getSeekBarPreference();

        final int minValue = pref.getMinValue();
        final int maxValue = pref.getMaxValue();
        final int stepSize = pref.getStepSize();
        final int divisor = pref.getDivisor();
        final int keyStepSize = pref.getKeyStepSize();
        final int progressOffset = pref.getProgressOffset();
        final String suffix = pref.getSuffix();

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(6, 6, 6, 6);

        TextView splashText = new TextView(context);
        splashText.setPadding(30, 10, 30, 10);
        CharSequence dialogMessage = pref.getDialogMessage();
        if (dialogMessage != null) {
            splashText.setText(dialogMessage);
        }
        layout.addView(splashText);

        valueText = new TextView(context);
        valueText.setGravity(Gravity.CENTER_HORIZONTAL);
        valueText.setTextSize(32);
        // Default text for value; hides bug where OnSeekBarChangeListener isn't called when opacity is 0%
        valueText.setText("0%");
        layout.addView(valueText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(context);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean b) {
                int value = progress + progressOffset;

                if (value < minValue) {
                    seekBar.setProgress(minValue - progressOffset);
                    return;
                }

                // floorDiv rather than / so step rounding stays correct for negative values
                int roundedValue = Math.floorDiv(value + (stepSize - 1), stepSize) * stepSize;
                if (roundedValue != value) {
                    seekBar.setProgress(roundedValue - progressOffset);
                    return;
                }

                String t;
                if (divisor != 1) {
                    float floatValue = roundedValue / (float) divisor;
                    t = String.format((Locale) null, "%.1f", floatValue);
                }
                else {
                    t = String.valueOf(value);
                }
                valueText.setText(suffix == null ? t : t.concat(suffix.length() > 1 ? " " + suffix : suffix));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        seekBar.setMax(maxValue - progressOffset);
        if (keyStepSize != 0) {
            seekBar.setKeyProgressIncrement(keyStepSize);
        }
        seekBar.setProgress(pref.getProgress() - progressOffset);

        return layout;
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult && seekBar != null) {
            getSeekBarPreference().commitProgress(
                    seekBar.getProgress() + getSeekBarPreference().getProgressOffset());
        }
    }
}
