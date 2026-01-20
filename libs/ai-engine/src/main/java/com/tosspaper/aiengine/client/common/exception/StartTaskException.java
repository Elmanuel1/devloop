package com.tosspaper.aiengine.client.common.exception;

/**
 * Exception thrown when start task operation fails.
 * Typically used for 422 validation errors (invalid schema).
 */
public class StartTaskException extends RuntimeException {
    
    public StartTaskException(String message) {
        super(message);
    }
    
    public StartTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
