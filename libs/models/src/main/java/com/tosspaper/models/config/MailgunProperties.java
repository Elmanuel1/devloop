package com.tosspaper.models.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Mailgun email service.
 */
@ConfigurationProperties(prefix = "app.mailgun")
@Data
@Validated
public class MailgunProperties {
    
    /**
     * Mailgun API key (required).
     */
    @NotBlank(message = "Mailgun API key is required")
    private String apiKey;
    
    /**
     * Mailgun domain (required).
     */
    @NotBlank(message = "Mailgun domain is required")
    private String domain;
    
    /**
     * Mailgun base URL (optional).
     * If not provided, defaults to US region (https://api.mailgun.net).
     */
    private String baseUrl;
    
    /**
     * From email address (required).
     */
    @NotBlank(message = "Mailgun from email is required")
    private String fromEmail;
}

