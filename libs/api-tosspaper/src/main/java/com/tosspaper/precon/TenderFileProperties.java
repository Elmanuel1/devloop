package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.ContentType;
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
    private Set<ContentType> allowedContentTypes = Set.of(
            ContentType.APPLICATION_PDF,
            ContentType.IMAGE_PNG,
            ContentType.IMAGE_JPEG
    );

    /**
     * Maximum file size in bytes.
     * Default: 200MB
     */
    @NotNull
    @Min(1)
    private Long maxFileSizeBytes = 200 * 1024 * 1024L;

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
    private Map<ContentType, Set<String>> contentTypeExtensions = Map.of(
            ContentType.APPLICATION_PDF, Set.of("pdf"),
            ContentType.IMAGE_PNG, Set.of("png"),
            ContentType.IMAGE_JPEG, Set.of("jpg", "jpeg")
    );
}
