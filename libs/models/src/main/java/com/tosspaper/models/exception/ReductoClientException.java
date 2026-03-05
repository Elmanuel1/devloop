package com.tosspaper.models.exception;

/**
 * Thrown when a Reducto HTTP call fails (non-2xx response or network error).
 *
 * <p>This is a hard error that should cause the extraction to transition to
 * {@code FAILED} status. Contrast with
 * {@link ReductoIntermediateStatusException}, which signals that a task is
 * still in-flight and should not trigger failure.
 */
public class ReductoClientException extends RuntimeException {

    public ReductoClientException(String message) {
        super(message);
    }

    public ReductoClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
