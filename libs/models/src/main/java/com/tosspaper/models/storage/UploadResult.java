package com.tosspaper.models.storage;

import com.tosspaper.models.validation.ValidationResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * Represents the result of a file upload operation to external storage.
 * Contains upload metadata, success status, and error information.
 */
@Getter
@AllArgsConstructor
@Accessors(fluent = true)
public class UploadResult {
    
    private final String key;
    private final String checksum;
    private final long actualSizeBytes;
    private final String contentType;
    private final boolean uploadSuccessful;
    private final Throwable error;
    private final ValidationResult validationResult;
    private final Map<String, String> metadata;
    
    /**
     * Create a successful upload result.
     * 
     * @param key the S3 key where the file is stored
     * @param checksum the file checksum/hash
     * @param actualSizeBytes the actual uploaded file size
     * @param contentType the MIME content type
     * @return successful UploadResult
     */
    public static UploadResult success(String key, String checksum, long actualSizeBytes, String contentType) {
        return new UploadResult(key, checksum, actualSizeBytes, contentType, true, null, ValidationResult.valid(), null);
    }
    
    /**
     * Create a successful upload result with metadata.
     * 
     * @param key the S3 key where the file is stored
     * @param checksum the file checksum/hash
     * @param actualSizeBytes the actual uploaded file size
     * @param contentType the MIME content type
     * @param metadata the upload metadata
     * @return successful UploadResult with metadata
     */
    public static UploadResult success(String key, String checksum, long actualSizeBytes, String contentType, Map<String, String> metadata) {
        return new UploadResult(key, checksum, actualSizeBytes, contentType, true, null, ValidationResult.valid(), metadata);
    }
    
    /**
     * Create a failed upload result due to validation errors.
     * 
     * @param validationResult the validation result containing violations
     * @return failed UploadResult due to validation
     */
    public static UploadResult validationFailure(ValidationResult validationResult) {
        RuntimeException error = new RuntimeException("Validation failed: " + validationResult.getViolationMessage());
        return new UploadResult(null, null, 0, null, false, error, validationResult, null);
    }
    
    /**
     * Create a failed upload result due to arbitrary errors.
     * 
     * @param throwable the throwable that caused the failure
     * @return failed UploadResult due to arbitrary error
     */
    public static UploadResult failure(Throwable throwable) {
        return new UploadResult(null, null, 0, null, false, throwable, ValidationResult.valid(), null);
    }
    
    /**
     * Create a failed upload result due to arbitrary errors.
     * 
     * @param message the error message
     * @return failed UploadResult due to arbitrary error
     */
    public static UploadResult failure(String message) {
        RuntimeException error = new RuntimeException(message);
        return new UploadResult(null, null, 0, null, false, error, ValidationResult.valid(), null);
    }
    
    /**
     * Check if the upload was successful.
     * 
     * @return true if upload succeeded
     */
    public boolean isSuccessful() {
        return uploadSuccessful;
    }
    
    /**
     * Check if the upload failed.
     * 
     * @return true if upload failed
     */
    public boolean isFailed() {
        return !uploadSuccessful;
    }
    
    /**
     * Get error throwable if upload failed.
     * 
     * @return Throwable or null if successful
     */
    public Throwable getError() {
        return error;
    }
    
    /**
     * Get error message if upload failed.
     * 
     * @return error message or null if successful
     */
    public String getErrorMessage() {
        return error != null ? error.getMessage() : null;
    }
    
    /**
     * Check if the failure was due to validation errors.
     * 
     * @return true if failed due to validation errors
     */
    public boolean isValidationFailure() {
        return isFailed() && validationResult != null && validationResult.isInvalid();
    }
    
    /**
     * Check if the failure was due to arbitrary errors (S3, network, etc.).
     * 
     * @return true if failed due to arbitrary errors
     */
    public boolean isArbitraryFailure() {
        return isFailed() && (validationResult == null || validationResult.isValid());
    }
    
    /**
     * Check if the failure was due to S3 errors.
     * 
     * @return true if failed due to S3 errors
     */
    public boolean isS3Failure() {
        return isFailed() && error instanceof software.amazon.awssdk.services.s3.model.S3Exception;
    }
    
    /**
     * Check if the failure was due to network errors.
     * 
     * @return true if failed due to network errors
     */
    public boolean isNetworkFailure() {
        return isFailed() && error instanceof java.net.ConnectException;
    }
    
    /**
     * Get the validation result.
     * 
     * @return ValidationResult or null if not a validation failure
     */
    public ValidationResult getValidationResult() {
        return validationResult;
    }
    
    /**
     * Get the list of validation violations.
     * 
     * @return list of violation messages or empty list if not a validation failure
     */
    public List<String> getValidationViolations() {
        return validationResult != null ? validationResult.getViolations() : List.of();
    }
}