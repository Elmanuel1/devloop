package com.tosspaper.everything.config;

import com.tosspaper.models.properties.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * AWS configuration class that sets up AWS services with credentials.
 * Uses DefaultCredentialsProvider which automatically picks up credentials from:
 * - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * - System properties
 * - ~/.aws/credentials file
 * - IAM roles (ECS, EC2, Lambda)
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
@RequiredArgsConstructor
@Slf4j
public class AwsConfig {

    private final AwsProperties awsProperties;

    /**
     * Creates an S3 client configured with credentials, region, and optional custom endpoint.
     * Supports S3-compatible services like LocalStack, MinIO, DigitalOcean Spaces, Wasabi, etc.
     *
     * @return configured S3Client instance
     */
    @Bean
    public S3Client s3Client() {
        log.info("Creating S3Client - region: {}, endpoint: {}",
                awsProperties.getBucket().getRegion(),
                awsProperties.getBucket().getEndpoint() != null ? awsProperties.getBucket().getEndpoint() : "(AWS default)");

        var clientBuilder = S3Client.builder()
            .region(Region.of(awsProperties.getBucket().getRegion()))
            .credentialsProvider(DefaultCredentialsProvider.create());

        // Configure custom endpoint if provided (for LocalStack or S3-compatible services)
        String endpoint = awsProperties.getBucket().getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            clientBuilder.endpointOverride(URI.create(endpoint.trim()));
            log.info("Using custom S3 endpoint: {}", endpoint);
        }

        clientBuilder.serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(awsProperties.getBucket().isPathStyleAccess())
            .build());

        return clientBuilder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        log.info("Creating S3Presigner - region: {}, endpoint: {}, pathStyle: {}",
                awsProperties.getBucket().getRegion(),
                awsProperties.getBucket().getEndpoint() != null ? awsProperties.getBucket().getEndpoint() : "(AWS default)",
                awsProperties.getBucket().isPathStyleAccess());

        var builder = S3Presigner.builder()
                .region(Region.of(awsProperties.getBucket().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(awsProperties.getBucket().isPathStyleAccess())
                        .build());

        // Add endpoint override if provided (for LocalStack or custom S3-compatible services)
        String endpoint = awsProperties.getBucket().getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            log.info("Using custom S3Presigner endpoint: {}", endpoint);
        }

        return builder.build();
    }
}
