package com.tosspaper.precon;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Set;

/**
 * Configuration properties for tender file handling.
 */
@ConfigurationProperties(prefix = "tender.file")
@Data
@Validated
public class TenderFileProperties {

    /**
     * S3 bucket for tender document uploads.
     */
    @NotEmpty
    private String uploadBucket;

    /**
     * Allowed content types for tender document uploads.
     */
    @NotEmpty
    private Set<String> allowedContentTypes = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "application/zip"
    );

    /**
     * Maximum file size in bytes.
     * Default: 50MB
     */
    @NotNull
    @Min(1)
    private Long maxFileSizeBytes = 50 * 1024 * 1024L;

    /**
     * Maximum filename length.
     */
    @NotNull
    @Min(1)
    private Integer maxFilenameLength = 255;

    /**
     * Mapping from content type to allowed file extensions.
     */
    @NotEmpty
    private Map<String, Set<String>> contentTypeExtensions = Map.of(
            "application/pdf", Set.of("pdf"),
            "image/png", Set.of("png"),
            "image/jpeg", Set.of("jpg", "jpeg"),
            "application/zip", Set.of("zip")
    );
}
