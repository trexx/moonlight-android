package com.limelight.nvstream.http;

import java.io.IOException;

/**
 * An error the host reported inside a successful HTTP response.
 *
 * <p>The host's API signals failure in the XML body rather than through status codes, so this
 * carries the host's own error code and message — which is what makes messages like "not paired"
 * or "app not found" surfaceable to the user.
 */
public class HostHttpResponseException extends IOException {
    private static final long serialVersionUID = 1543508830807804222L;
    
    private int errorCode;
    private String errorMsg;
    
    /** @param errorCode the host's own error code, whose meaning is defined by the host software */
    public HostHttpResponseException(int errorCode, String errorMsg) {
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
    }
    
    /** @return the host's error code, whose meaning is defined by the host software */
    public int getErrorCode() {
        return errorCode;
    }
    
    
    /** {@inheritDoc} @return the host's message with its error code appended */
    @Override
    public String getMessage() {
        return "Host PC returned error: "+errorMsg+" (Error code: "+errorCode+")";
    }
}
