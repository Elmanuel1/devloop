package com.tosspaper.models.properties;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the messaging system provider switch.
 * Controls whether Redis Streams or SQS is used for async messaging.
 */
@ConfigurationProperties(prefix = "messaging")
@Data
@Validated
@Slf4j
public class MessagingProperties {

    /**
     * The messaging provider to use.
     * - "redis": Use Redis Streams (default)
     * - "sqs": Use AWS SQS
     */
    private String provider = "redis";

    @PostConstruct
    public void logConfig() {
        log.info("=== Messaging Configuration ===");
        log.info("Messaging Provider: {}", provider);
    }

    public boolean isRedis() {
        return "redis".equalsIgnoreCase(provider);
    }

    public boolean isSqs() {
        return "sqs".equalsIgnoreCase(provider);
    }
}
