package com.tosspaper.models.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for application email settings.
 */
@ConfigurationProperties(prefix = "app.email")
@Data
@Validated
public class AppEmailProperties {

    /**
     * Allowed domain for email processing and assigned email generation (required).
     * Format: {random6digits}@{company-name}.{allowedDomain}
     */
    @NotBlank(message = "Allowed domain is required")
    private String allowedDomain;
}
