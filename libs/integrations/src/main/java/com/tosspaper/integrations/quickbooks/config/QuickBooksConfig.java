package com.tosspaper.integrations.quickbooks.config;

import com.intuit.oauth2.client.OAuth2PlatformClient;
import com.intuit.oauth2.config.Environment;
import com.intuit.oauth2.config.OAuth2Config;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for QuickBooks Online integration beans.
 */
@Configuration
@RequiredArgsConstructor
public class QuickBooksConfig {

    private final QuickBooksProperties properties;

    @Bean
    public OAuth2PlatformClient oAuth2PlatformClient() {
        Environment env = properties.getApiBaseUrl().contains("sandbox") ? Environment.SANDBOX : Environment.PRODUCTION;
        OAuth2Config config = new OAuth2Config.OAuth2ConfigBuilder(properties.getClientId(), properties.getClientSecret())
                .callDiscoveryAPI(env)
                .buildConfig();
        
        return new OAuth2PlatformClient(config);
    }
}

