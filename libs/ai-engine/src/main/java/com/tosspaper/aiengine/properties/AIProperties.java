package com.tosspaper.aiengine.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Unified configuration properties for AI providers.
 * Contains common settings and provider selection.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai")
public class AIProperties {
    
    /**
     * Active AI provider: "reducto"
     */
    private String provider = "reducto";
    
    /**
     * Common API key for AI provider authentication.
     */
    @NotBlank(message = "API key is required")
    private String apiKey;
    
    /**
     * Common base URL for AI provider API endpoints.
     */
    private String baseUrl;
    
}

