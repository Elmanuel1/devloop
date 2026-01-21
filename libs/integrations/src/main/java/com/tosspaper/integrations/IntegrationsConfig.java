package com.tosspaper.integrations;

import com.tosspaper.integrations.config.IntegrationEncryptionProperties;
import com.tosspaper.integrations.config.IntegrationProperties;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class for the integrations' module.
 * Enables configuration properties for all integration providers.
 */
@Configuration
@EnableConfigurationProperties({
        QuickBooksProperties.class,
        IntegrationEncryptionProperties.class,
        IntegrationProperties.class
})
public class IntegrationsConfig {
}
