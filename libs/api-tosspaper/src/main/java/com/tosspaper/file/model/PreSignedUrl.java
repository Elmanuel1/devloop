package com.tosspaper.file.model;

import java.time.OffsetDateTime;

/**
 * Represents a presigned URL for file operations
 */
public record PreSignedUrl(
    String url,
    OffsetDateTime expiration
) {
    
    /**
     * Check if the presigned URL has expired
     */
    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiration);
    }
    
    /**
     * Get the remaining time until expiration in seconds
     */
    public long getSecondsUntilExpiration() {
        return java.time.Duration.between(OffsetDateTime.now(), expiration).getSeconds();
    }
} 