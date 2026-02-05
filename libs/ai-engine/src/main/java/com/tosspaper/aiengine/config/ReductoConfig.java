package com.tosspaper.aiengine.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.client.reducto.ReductoClient;
import com.tosspaper.aiengine.properties.AIProperties;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Reducto AI client.
 */
@Configuration
public class ReductoConfig {
    
    @Bean
    public ReductoClient reductoClient(AIProperties aiProperties, OkHttpClient httpClient, ObjectMapper objectMapper) {
        return new ReductoClient(aiProperties.getApiKey(), httpClient, objectMapper, aiProperties.getWebhookChannel());
    }
}
