package com.tosspaper.aiengine.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

/**
 * Configuration properties for HTTP client configuration.
 */
@ConfigurationProperties(prefix = "ai.http")
@Data
@Validated
public class HttpProperties {
    
    
    /**
     * Connection timeout in seconds.
     */
    @NotNull
    private Integer connectTimeoutSeconds = 30;
    
    /**
     * Read timeout in seconds.
     */
    @NotNull
    private Integer readTimeoutSeconds = 60;
    
    /**
     * Write timeout in seconds.
     */
    @NotNull
    private Integer writeTimeoutSeconds = 60;
    
    /**
     * Whether to enable HTTP request/response logging.
     */
    @NotNull
    private Boolean enableLogging = true;
    
    /**
     * Logging level for HTTP requests.
     * Values: NONE, BASIC, HEADERS, BODY
     */
    @NotBlank
    private String loggingLevel = "BODY";
}
