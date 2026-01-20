package com.tosspaper.aiengine.client.reducto.exception;

/**
 * Exception for Reducto upload failures.
 */
public class ReductoUploadException extends ReductoException {
    
    public ReductoUploadException(String message) {
        super(message);
    }
    
    public ReductoUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
