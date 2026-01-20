package com.tosspaper.models.properties;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for AWS SQS.
 * Used when messaging.provider=sqs is set.
 *
 * Credentials are handled by DefaultCredentialsProvider which automatically picks up from:
 * - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * - System properties
 * - ~/.aws/credentials file
 * - IAM roles (ECS, EC2, Lambda)
 */
@ConfigurationProperties(prefix = "aws.sqs")
@Data
@Validated
@Slf4j
public class SqsProperties {

    /**
     * AWS region for SQS queues.
     * Defaults to us-east-1.
     */
    private String region = "us-east-1";

    /**
     * Custom SQS endpoint URL (optional).
     * Leave empty for AWS, set for LocalStack (e.g., http://localhost:4566).
     */
    private String endpoint;

    /**
     * Prefix for queue names.
     * Full queue name = prefix + "-" + queueName
     * Example: "prod-tosspaper" results in "prod-tosspaper-email-local-uploads"
     */
    private String queuePrefix = "tosspaper";

    /**
     * Per-queue configuration.
     * Key is the queue name (e.g., "email-local-uploads").
     */
    @Valid
    private Map<String, QueueConfig> queues = new HashMap<>();

    @PostConstruct
    public void logConfig() {
        log.info("=== SQS Configuration ===");
        log.info("SQS Region: {}", region);
        log.info("SQS Endpoint: {}", endpoint != null ? endpoint : "(AWS default)");
        log.info("Queue Prefix: {}", queuePrefix);
        log.info("Configured queues: {}", queues.keySet());
    }

    @Data
    @Validated
    public static class QueueConfig {

        /**
         * Visibility timeout in seconds.
         * How long a message is hidden from other consumers after being received.
         * Should be longer than expected processing time.
         */
        private int visibilityTimeoutSeconds = 30;

        /**
         * Maximum number of receives before moving to DLQ.
         * After this many failed processing attempts, message goes to dead-letter queue.
         */
        private int maxReceiveCount = 3;

        /**
         * Long poll wait time in seconds.
         * How long to wait for messages when polling (0-20).
         * Higher values reduce empty responses and API costs.
         */
        private int pollDelaySeconds = 20;

        /**
         * Maximum messages per poll (1-10).
         * Batch size for receiving messages.
         */
        private int maxMessages = 10;

        /**
         * Whether this queue consumer is enabled.
         * Set to false to disable processing for this queue.
         */
        private boolean enabled = true;
    }
}
