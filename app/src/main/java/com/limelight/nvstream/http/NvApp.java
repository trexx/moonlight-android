package com.limelight.nvstream.http;

import com.limelight.LimeLog;

/** One launchable app on a host: its name, host-assigned ID, and whether it supports HDR. */
public class NvApp {
    private String appName = "";
    private int appId;
    private boolean initialized;
    private boolean hdrSupported;
    
    /** Creates an empty app, to be filled in while parsing an app list. */
    public NvApp() {}
    
    /** Creates a name-only app, for placeholders with no host-assigned ID yet. */
    public NvApp(String appName) {
        this.appName = appName;
    }
    
    /** Creates a fully populated app. */
    public NvApp(String appName, int appId, boolean hdrSupported) {
        this.appName = appName;
        this.appId = appId;
        this.hdrSupported = hdrSupported;
        this.initialized = true;
    }
    
    /** Sets the display name. */
    public void setAppName(String appName) {
        this.appName = appName;
    }
    
    /** Sets the ID from its string form in the host's XML. */
    public void setAppId(String appId) {
        try {
            this.appId = Integer.parseInt(appId);
            this.initialized = true;
        } catch (NumberFormatException e) {
            LimeLog.warning("Malformed app ID: "+appId);
        }
    }
    
    /** Sets the host-assigned app ID. */
    public void setAppId(int appId) {
        this.appId = appId;
        this.initialized = true;
    }

    /** Records whether the host can stream this app in HDR. */
    public void setHdrSupported(boolean hdrSupported) {
        this.hdrSupported = hdrSupported;
    }
    
    /** @return the display name */
    public String getAppName() {
        return this.appName;
    }
    
    /** @return the host-assigned app ID */
    public int getAppId() {
        return this.appId;
    }

    /** @return true if the host reports HDR support for this app */
    public boolean isHdrSupported() {
        return this.hdrSupported;
    }
    
    /** @return true if this app has a usable ID, as opposed to being a name-only placeholder */
    public boolean isInitialized() {
        return this.initialized;
    }

    /** @return the app name, so this can be used directly in adapters */
    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Name: ").append(appName).append("\n");
        str.append("HDR Supported: ").append(hdrSupported ? "Yes" : "Unknown").append("\n");
        str.append("ID: ").append(appId).append("\n");
        return str.toString();
    }
}
