package com.tosspaper.aiengine.client.reducto.exception;

/**
 * Exception for Reducto task creation/retrieval failures.
 */
public class ReductoTaskException extends ReductoException {
    
    public ReductoTaskException(String message) {
        super(message);
    }
    
    public ReductoTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
