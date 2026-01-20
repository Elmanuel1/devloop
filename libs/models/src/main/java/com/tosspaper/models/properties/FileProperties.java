package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Set;

/**
 * Configuration properties for file handling.
 */
@ConfigurationProperties(prefix = "file")
@Data
@Validated
public class FileProperties {
    
    /**
     * Minimum file size in bytes.
     * Files smaller than this are likely signature icons and will be rejected.
     * Default: 5KB
     */
    @NotNull
    @Min(0)
    private Long minFileSizeBytes = 5 * 1024L;

    /**
     * Maximum file size in bytes.
     */
    @NotNull
    @Min(1)
    private Long maxFileSizeBytes = 3 * 1024 * 1024L; 
    
    /**
     * Maximum filename length.
     */
    @NotNull
    @Min(1)
    private Integer maxFilenameLength = 255;
    
    /**
     * Allowed content types.
     */
    @NotEmpty
    private Set<String> allowedContentTypes = Set.of(
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/gif",
        "image/webp"
    );
    
    /**
     * Allowed file extensions (without dot).
     */
    @NotEmpty
    private Set<String> allowedFileExtensions = Set.of(
        "pdf", "jpg", "jpeg", "png", "webp"
    );
    
    /**
     * Map of forbidden characters to their replacement strings.
     */
    @NotEmpty
    private Map<String, String> replacementMap = Map.of(
        "..", "_",
        "/", "_",
        "\\", "_",
        ":", "_",
        "*", "_",
        "?", "_",
        "\"", "_",
        "<", "_",
        ">", "_",
        "|", "_"
    );
    
    /**
     * Path where files are stored in the filesystem.
     */
    @NotNull
    private String filesystemPath = "/tmp/email-attachments";
    
    /**
     * Minimum image width in pixels.
     * Images smaller than this are likely signature icons and will be rejected.
     * Default: 100px
     */
    @NotNull
    @Min(1)
    private Integer minImageWidth = 100;

    /**
     * Minimum image height in pixels.
     * Images smaller than this are likely signature icons and will be rejected.
     * Default: 100px
     */
    @NotNull
    @Min(1)
    private Integer minImageHeight = 100;

    /**
     * Minimum image area in pixels (width × height).
     * Images with fewer total pixels are likely signature elements (logos, icons).
     * Default: 240,000 (e.g., 400x600 or 600x400)
     */
    @NotNull
    @Min(0)
    private Long minImageArea = 240_000L;

    /**
     * Minimum acceptable aspect ratio for images (width/height).
     * Images with aspect ratio below this value will be rejected.
     * Default: 0.3 (rejects images taller than 1:3, covers receipts but blocks extreme strips).
     */
    @NotNull
    @Min(0)
    private Double minAspectRatio = 0.3;

    /**
     * Maximum acceptable aspect ratio for images (width/height).
     * Images with aspect ratio above this value will be rejected.
     * Default: 3.0 (rejects images wider than 3:1, e.g., banner/header images).
     */
    @NotNull
    @Min(0)
    private Double maxAspectRatio = 3.0;
    
}
