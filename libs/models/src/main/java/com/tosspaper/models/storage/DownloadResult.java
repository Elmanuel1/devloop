package com.tosspaper.models.storage;

import com.tosspaper.models.domain.FileObject;
import lombok.Builder;
import lombok.Data;

/**
 * Result of a file download operation.
 * Contains the downloaded file data and metadata about the operation.
 */
@Data
@Builder
public class DownloadResult {
    
    /**
     * The storage key that was downloaded.
     */
    private final String key;
    
    /**
     * The downloaded file object.
     */
    private final FileObject fileObject;
    
    /**
     * Whether the download was successful.
     */
    private final boolean successful;
    
    /**
     * Error message if download failed.
     */
    private final String error;
    
    /**
     * The throwable that caused the failure.
     */
    private final Throwable throwable;
    
    /**
     * Additional metadata from the download operation.
     */
    private final java.util.Map<String, Object> metadata;
    
    /**
     * Create a successful download result.
     * 
     * @param key the storage key
     * @param fileObject the downloaded file object
     * @return successful DownloadResult
     */
    public static DownloadResult success(String key, FileObject fileObject) {
        return DownloadResult.builder()
            .key(key)
            .fileObject(fileObject)
            .successful(true)
            .build();
    }
    
    /**
     * Create a successful download result with metadata.
     * 
     * @param key the storage key
     * @param fileObject the downloaded file object
     * @param metadata additional metadata
     * @return successful DownloadResult
     */
    public static DownloadResult success(String key, FileObject fileObject, java.util.Map<String, Object> metadata) {
        return DownloadResult.builder()
            .key(key)
            .fileObject(fileObject)
            .successful(true)
            .metadata(metadata)
            .build();
    }
    
    /**
     * Create a failed download result.
     * 
     * @param key the storage key
     * @param error the error message
     * @return failed DownloadResult
     */
    public static DownloadResult failure(String key, String error) {
        return DownloadResult.builder()
            .key(key)
            .successful(false)
            .error(error)
            .build();
    }
    
    /**
     * Create a failed download result with throwable.
     * 
     * @param key the storage key
     * @param throwable the throwable that caused the failure
     * @return failed DownloadResult
     */
    public static DownloadResult failure(String key, Throwable throwable) {
        return DownloadResult.builder()
            .key(key)
            .successful(false)
            .error(throwable.getMessage())
            .throwable(throwable)
            .build();
    }
    
    /**
     * Check if the download failed.
     * 
     * @return true if download failed
     */
    public boolean isFailed() {
        return !successful;
    }
}
