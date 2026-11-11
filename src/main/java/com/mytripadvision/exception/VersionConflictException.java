package com.mytripadvision.exception;

/** The thread changed while the request was running (for example two review answers at once). */
public class VersionConflictException extends RuntimeException {

    public VersionConflictException() {
        super("The chat was changed by another request. Reload it and try again.");
    }
}
