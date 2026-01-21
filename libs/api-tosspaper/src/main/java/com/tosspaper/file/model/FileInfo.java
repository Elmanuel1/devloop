package com.tosspaper.file.model;

import java.time.OffsetDateTime;

/**
 * Represents file information and metadata
 */
public record FileInfo(
    String name,
    Long size,
    String contentType,
    OffsetDateTime lastModified
) {
    
    /**
     * Constructor for basic file info without last modified date
     */
    public FileInfo(String name, Long size, String contentType) {
        this(name, size, contentType, null);
    }
    
    /**
     * Constructor for file listing scenarios with just name and size
     */
    public FileInfo(String name, Long size) {
        this(name, size, null, null);
    }
} 