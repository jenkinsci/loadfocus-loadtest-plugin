package com.loadfocus.jenkins.api;

/** The LoadFocus API answered, but not with what the plugin needs. Message is safe to print in a build log. */
public class LoadAPIException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int status;

    public LoadAPIException(String message) {
        this(message, 0);
    }

    public LoadAPIException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** HTTP status of the response, or 0 when not applicable. */
    public int getStatus() {
        return status;
    }

    /** A gateway/maintenance error or rate limit that is worth retrying. */
    public boolean isTransient() {
        return status >= 500 || status == 429;
    }
}
