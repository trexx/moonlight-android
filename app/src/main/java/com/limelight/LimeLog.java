package com.limelight;

import java.util.logging.Logger;

/**
 * Logging facade for the app and for moonlight-common-c's Java bindings.
 *
 * <p>Wraps {@link java.util.logging.Logger} rather than {@code android.util.Log} because this code
 * is shared with non-Android builds of the common library. On Android these calls surface through
 * logcat as usual.
 */
public class LimeLog {
    private static final Logger LOGGER = Logger.getLogger(LimeLog.class.getName());

    /** Logs routine progress: connection stages, decoder selection, device enumeration. */
    public static void info(String msg) {
        LOGGER.info(msg);
    }
    
    /** Logs something unexpected that the app recovered from. */
    public static void warning(String msg) {
        LOGGER.warning(msg);
    }
    
    /** Logs a failure the user is likely to notice. */
    public static void severe(String msg) {
        LOGGER.severe(msg);
    }
    
}
