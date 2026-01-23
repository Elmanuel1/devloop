package com.tosspaper.integrations.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for token encryption.
 * The encryption key must be a base64-encoded 256-bit AES key.
 */
@ConfigurationProperties(prefix = "app.integrations.encryption")
@Getter
@Setter
@ToString(exclude = "key")
@Validated
public class IntegrationEncryptionProperties {

    /**
     * Base64-encoded 256-bit AES key for encrypting OAuth tokens.
     * This key must be kept secret and should be generated using TokenEncryptionUtil.generateKey().
     * Required for token encryption/decryption.
     */
    @NotBlank(message = "Integration encryption key is required")
    private String key;
}

