package com.tosspaper.models.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for frontend URL.
 */
@Configuration
@ConfigurationProperties(prefix = "app.frontend")
@Validated
@Data
public class FrontendUrlProperties {
    
    /**
     * Frontend base URL (e.g., https://devapp.tosspaper.com).
     * Required - must be configured in application properties.
     */
    @NotBlank(message = "Frontend base URL is required")
    private String baseUrl;
}

