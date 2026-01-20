package com.tosspaper.models.utils;

import com.tosspaper.models.domain.FileObject;

import java.util.UUID;

/**
 * Utility class for S3 key generation and manipulation.
 */
public class S3KeyUtils {

    /**
     * Generates a unique S3 key for the file using the provided key prefix and filename.
     * Format: {key}/{uuid}_{filename}
     * 
     * @param keyPrefix the S3 key prefix for organizing the file
     * @param fileObject the file object containing the filename
     * @return the generated S3 key
     */
    public static String generateS3Key(String keyPrefix, FileObject fileObject) {
        String uniqueId = UUID.randomUUID().toString();
        
        // Sanitize filename to remove problematic characters
        String sanitizedFilename = fileObject.getFileName()
            .replaceAll("[^a-zA-Z0-9._-]", "_")
            .replaceAll("_{2,}", "_");
        
        // Ensure key prefix ends with / if not empty
        String normalizedPrefix = keyPrefix;
        if (normalizedPrefix != null && !normalizedPrefix.isEmpty() && !normalizedPrefix.endsWith("/")) {
            normalizedPrefix += "/";
        }
        
        return String.format("%s%s_%s", 
            normalizedPrefix != null ? normalizedPrefix : "",
            uniqueId,
            sanitizedFilename);
    }
    
    /**
     * Extract filename from storage key.
     * 
     * @param key the storage key
     * @return filename extracted from key
     */
    public static String extractFileNameFromKey(String key) {
        if (key == null || key.isEmpty()) {
            return "unknown";
        }
        
        int lastSlashIndex = key.lastIndexOf('/');
        if (lastSlashIndex >= 0 && lastSlashIndex < key.length() - 1) {
            return key.substring(lastSlashIndex + 1);
        }
        
        return key;
    }
    
    /**
     * Extract directory path from storage key.
     * 
     * @param key the storage key
     * @return directory path (without filename)
     */
    public static String extractDirectoryFromKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        
        int lastSlashIndex = key.lastIndexOf('/');
        if (lastSlashIndex > 0) {
            return key.substring(0, lastSlashIndex);
        }
        
        return "";
    }
    
    /**
     * Check if a key represents a file (has a filename).
     * 
     * @param key the storage key
     * @return true if key represents a file
     */
    public static boolean isFileKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        
        int lastSlashIndex = key.lastIndexOf('/');
        return lastSlashIndex < key.length() - 1;
    }
}
