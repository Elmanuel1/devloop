package com.tosspaper.integrations.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * System-level configuration properties for integrations.
 * These settings apply to all integration providers.
 */
@ConfigurationProperties(prefix = "app.integrations")
@Data
@Validated
public class IntegrationProperties {

    /**
     * Minutes before token expiry to trigger refresh.
     * Tokens should be refreshed if they expire within this time.
     * Default: 10 minutes.
     */
    private int tokenRefreshThresholdMinutes = 10;
}
