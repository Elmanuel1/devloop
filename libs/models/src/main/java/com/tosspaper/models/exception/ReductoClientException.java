package com.tosspaper.models.exception;

/**
 * Thrown when a Reducto HTTP call fails (non-2xx response or network error).
 * Causes the extraction to transition to {@code FAILED} status, unlike
 * {@link ReductoIntermediateStatusException} which signals a still in-flight task.
 */
public class ReductoClientException extends RuntimeException {

    public ReductoClientException(String message) {
        super(message);
    }

    public ReductoClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
