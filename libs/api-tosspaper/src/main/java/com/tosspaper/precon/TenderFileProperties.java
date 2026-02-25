package com.tosspaper.precon;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotEmpty;

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
}
