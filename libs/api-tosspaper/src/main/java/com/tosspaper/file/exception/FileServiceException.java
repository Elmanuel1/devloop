package com.tosspaper.file.exception;

/**
 * Base exception for file service operations
 */
public class FileServiceException extends RuntimeException {
    
    public FileServiceException(String message) {
        super(message);
    }
    
    public FileServiceException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public FileServiceException(Throwable cause) {
        super(cause);
    }
    
    public String getCode() {
        return "file.service.error";
    }
} 