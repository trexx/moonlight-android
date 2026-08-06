package com.limelight.preferences;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import com.limelight.utils.HelpLauncher;

/** Preference that opens a URL when tapped, for the help and troubleshooting entries. */
public class WebLauncherPreference extends Preference {
    private String url;

    /** {@inheritDoc} */
    public WebLauncherPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(attrs);
    }

    /** {@inheritDoc} */
    public WebLauncherPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize(attrs);
    }

    /** {@inheritDoc} */
    public WebLauncherPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize(attrs);
    }

    private void initialize(AttributeSet attrs) {
        if (attrs == null) {
            throw new IllegalStateException("WebLauncherPreference must have attributes!");
        }

        url = attrs.getAttributeValue(null, "url");
        if (url == null) {
            throw new IllegalStateException("WebLauncherPreference must have 'url' attribute!");
        }
    }

    /** {@inheritDoc} Opens the configured URL, in a browser or the in-app help activity. */
    @Override
    public void onClick() {
        HelpLauncher.launchUrl(getContext(), url);
    }
}
