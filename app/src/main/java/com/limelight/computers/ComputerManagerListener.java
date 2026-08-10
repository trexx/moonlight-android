package com.limelight.computers;

import com.limelight.nvstream.http.ComputerDetails;

/**
 * Receives host state updates from {@link ComputerManagerService}'s polling.
 *
 * <p>Called from polling threads, once per completed poll per host, so implementations post to the
 * UI thread before touching views.
 */
public interface ComputerManagerListener {
    void notifyComputerUpdated(ComputerDetails details);
}
