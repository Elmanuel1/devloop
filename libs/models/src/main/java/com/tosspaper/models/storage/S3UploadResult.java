package com.tosspaper.models.storage;

import com.tosspaper.models.validation.ValidationResult;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * S3-specific upload result that extends UploadResult with S3 metadata.
 * Contains S3-specific information like region, bucket, and other S3 details.
 */
@Getter
@Accessors(fluent = true)
public class S3UploadResult extends UploadResult {
    
    private final String region;
    private final String endpoint;
    private final String bucket;
    private final String etag;
    private final String versionId;
    
    public S3UploadResult(String key, String checksum, long actualSizeBytes, String contentType,
                         boolean uploadSuccessful, Throwable error, ValidationResult validationResult,
                         Map<String, String> metadata, String region, String bucket, String etag, String versionId, String endpoint) {
        super(key, checksum, actualSizeBytes, contentType, uploadSuccessful, error, validationResult, metadata);
        this.region = region;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.etag = etag;
        this.versionId = versionId;
    }
    
    /**
     * Create a successful S3 upload result.
     * 
     * @param key the S3 key where the file is stored
     * @param checksum the file checksum/hash
     * @param actualSizeBytes the actual uploaded file size
     * @param contentType the MIME content type
     * @param metadata the upload metadata
     * @param region the AWS region where the file is stored
     * @param bucket the S3 bucket name
     * @param etag the S3 ETag for the uploaded object
     * @param versionId the S3 version ID (if versioning is enabled)
     * @return successful S3UploadResult
     */
    public static S3UploadResult success(String key, String checksum, long actualSizeBytes, String contentType, 
                                       Map<String, String> metadata, String region, String bucket, 
                                       String etag, String versionId, String endpoint) {
        return new S3UploadResult(key, checksum, actualSizeBytes, contentType, true, null, 
                                ValidationResult.valid(), metadata, region, bucket, etag, versionId, endpoint);
    }
    
    /**
     * Create a failed S3 upload result due to validation errors.
     * 
     * @param validationResult the validation result containing violations
     * @return failed S3UploadResult due to validation
     */
    public static S3UploadResult validationFailure(ValidationResult validationResult) {
        RuntimeException error = new RuntimeException("Validation failed: " + validationResult.getViolationMessage());
        return new S3UploadResult(null, null, 0, null, false, error, validationResult, null, 
                                null, null, null, null, null);
    }
    
    /**
     * Create a failed S3 upload result due to arbitrary errors.
     * 
     * @param throwable the throwable that caused the failure
     * @return failed S3UploadResult due to arbitrary error
     */
    public static S3UploadResult failure(Throwable throwable) {
        return new S3UploadResult(null, null, 0, null, false, throwable, ValidationResult.valid(), 
                                null, null, null, null, null, null);
    }
    
    /**
     * Create a failed S3 upload result due to arbitrary errors.
     * 
     * @param message the error message
     * @return failed S3UploadResult due to arbitrary error
     */
    public static S3UploadResult failure(String message) {
        RuntimeException error = new RuntimeException(message);
        return new S3UploadResult(null, null, 0, null, false, error, ValidationResult.valid(), 
                                null, null, null, null, null, null);
    }
}