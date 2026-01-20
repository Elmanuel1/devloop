package com.tosspaper.aiengine.client.common.exception;

/**
 * Exception thrown when an extraction task is not found.
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String message) {
        super(message);
    }

    public TaskNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
