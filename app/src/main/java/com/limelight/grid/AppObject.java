package com.limelight.grid;

import com.limelight.nvstream.http.NvApp;

/**
 * One app as the grid sees it: what the host reported, plus the two pieces of state the host does
 * not carry — whether it is the app currently running, and whether the user has hidden it.
 *
 * <p>Lived inside {@code AppView} until the browsing activities were merged. Not a record: both
 * flags are rewritten in place as poll results arrive, and the grid holds the same instance
 * across those updates so a rebind does not have to find the cell again.
 */
public class AppObject {
    public final NvApp app;
    public boolean isRunning;
    public boolean isHidden;

    public AppObject(NvApp app) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        this.app = app;
    }

    @Override
    public String toString() {
        return app.getAppName();
    }
}
