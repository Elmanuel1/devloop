package com.tosspaper.models.properties;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for AWS services.
 * Contains S3 bucket configuration.
 *
 * Credentials are handled by DefaultCredentialsProvider which automatically picks up from:
 * - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * - System properties
 * - ~/.aws/credentials file
 * - IAM roles (ECS, EC2, Lambda)
 */
@ConfigurationProperties(prefix = "aws")
@Data
@Validated
@Slf4j
public class AwsProperties {

    @Valid
    private Bucket bucket = new Bucket();

    @PostConstruct
    public void logConfig() {
        log.info("=== AWS Configuration ===");
        log.info("AWS Region: {}", bucket.region);
        log.info("S3 Bucket: {}", bucket.name);
        log.info("S3 Endpoint: {}", bucket.endpoint != null && !bucket.endpoint.isEmpty() ? bucket.endpoint : "(AWS default)");
        log.info("Path-style access: {}", bucket.pathStyleAccess);
    }

    @Data
    @Validated
    public static class Bucket {
        @NotBlank(message = "AWS region is required")
        private String region = "us-east-1";

        @NotBlank(message = "S3 bucket name is required")
        private String name;

        /**
         * Custom S3-compatible endpoint URL (optional).
         * If null or empty, uses standard AWS S3 endpoints (production).
         * Examples:
         * - Dev via nginx: https://dev-api.tosspaper.com/s3
         * - LocalStack direct: http://localhost:4566
         */
        private String endpoint;

        /**
         * Enable path-style access for S3 requests.
         * - false (default): Uses virtual-hosted style (bucket.s3.region.amazonaws.com/key)
         * - true: Uses path-style (s3.region.amazonaws.com/bucket/key)
         *
         * Set to true for S3-compatible services that don't support virtual-hosted style
         * (e.g., LocalStack, MinIO, some DigitalOcean Spaces configurations).
         */
        private boolean pathStyleAccess = false;

        private boolean publicRead = false;

        private long maxFileSizeBytes = 2 * 1024 * 1024; // 2MB default
    }
}
