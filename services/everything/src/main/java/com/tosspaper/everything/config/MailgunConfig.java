package com.tosspaper.everything.config;

import com.mailgun.api.v3.MailgunMessagesApi;
import com.mailgun.client.MailgunClient;
import com.tosspaper.models.config.MailgunProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Mailgun email service.
 * Uses official Mailgun Java SDK (com.mailgun:mailgun-java)
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MailgunProperties.class)
@RequiredArgsConstructor
public class MailgunConfig {
    
    private final MailgunProperties mailgunProperties;
    
    @Bean
    public MailgunMessagesApi mailgunMessagesApi() {
        try {
            // Validate API key is present
            if (mailgunProperties.getApiKey() == null || mailgunProperties.getApiKey().isBlank()) {
                log.error("Mailgun API key is missing or blank");
                throw new RuntimeException("Mailgun API key is required but not configured");
            }
            
            // Log configuration (mask API key for security)
            String maskedKey = mailgunProperties.getApiKey().length() > 8 
                ? mailgunProperties.getApiKey().substring(0, 4) + "..." + mailgunProperties.getApiKey().substring(mailgunProperties.getApiKey().length() - 4)
                : "***";
            log.info("Configuring Mailgun client - Domain: {}, Base URL: {}, API Key: {}", 
                mailgunProperties.getDomain(), 
                mailgunProperties.getBaseUrl() != null ? mailgunProperties.getBaseUrl() : "US (default)",
                maskedKey);
            
            if (mailgunProperties.getBaseUrl() != null && !mailgunProperties.getBaseUrl().isBlank()) {
                // EU region or custom base URL
                return MailgunClient.config(mailgunProperties.getBaseUrl(), mailgunProperties.getApiKey())
                    .createApi(MailgunMessagesApi.class);
            } else {
                // US region (default)
                return MailgunClient.config(mailgunProperties.getApiKey())
                    .createApi(MailgunMessagesApi.class);
            }
        } catch (Exception e) {
            log.error("Failed to create Mailgun client", e);
            throw new RuntimeException("Failed to create Mailgun client", e);
        }
    }
}

