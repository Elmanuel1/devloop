package com.tosspaper.integrations.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for push retry behavior.
 * Controls maximum number of retry attempts before marking an entity as permanently failed.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "tosspaper.integration.push.retry")
public class PushRetryConfig {
    /**
     * Maximum number of retry attempts allowed before marking as permanently failed.
     * Default: 5 attempts
     */
    private int maxAttempts = 5;
}
