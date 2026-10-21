package org.promptoptimizer.ingest;

/** Raised when an input cannot be ingested safely. Never swallowed; callers must report it. */
public class InputRejectedException extends RuntimeException {
    public InputRejectedException(String message) {
        super(message);
    }

    public InputRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
