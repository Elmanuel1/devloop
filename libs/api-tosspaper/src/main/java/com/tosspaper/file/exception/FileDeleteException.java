package com.tosspaper.file.exception;

/**
 * Exception thrown when file delete operations fail
 */
public class FileDeleteException extends FileServiceException {
    
    public FileDeleteException(String message) {
        super(message);
    }
    
    public FileDeleteException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public FileDeleteException(Throwable cause) {
        super(cause);
    }
    
    @Override
    public String getCode() {
        return "file.delete.error";
    }
} 