package com.tosspaper.file.exception;

/**
 * Exception thrown when file upload operations fail
 */
public class FileUploadException extends FileServiceException {
    
    public FileUploadException(String message) {
        super(message);
    }
    
    public FileUploadException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public FileUploadException(Throwable cause) {
        super(cause);
    }
    
    @Override
    public String getCode() {
        return "file.upload.error";
    }
} 