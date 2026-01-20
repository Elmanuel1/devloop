package com.tosspaper.aiengine.client.reducto.exception;

/**
 * Base exception for Reducto operations.
 */
public class ReductoException extends RuntimeException {
    
    public ReductoException(String message) {
        super(message);
    }
    
    public ReductoException(String message, Throwable cause) {
        super(message, cause);
    }
}
