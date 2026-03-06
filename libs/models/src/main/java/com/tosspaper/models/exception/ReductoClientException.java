package com.tosspaper.models.exception;

/** Thrown when a Reducto HTTP call fails. */
public class ReductoClientException extends RuntimeException {

    public ReductoClientException(String message) {
        super(message);
    }

    public ReductoClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
