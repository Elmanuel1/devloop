package com.tosspaper.everything.config;

import com.tosspaper.models.properties.SqsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

/**
 * AWS SQS configuration class.
 * Only activated when messaging.provider=sqs.
 * Uses DefaultCredentialsProvider, which automatically picks up credentials from:
 * - Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
 * - System properties
 * - ~/.aws/credentials file
 * - IAM roles (ECS, EC2, Lambda)
 */
@Configuration
@ConditionalOnProperty(name = "messaging.provider", havingValue = "sqs")
@EnableConfigurationProperties(SqsProperties.class)
@RequiredArgsConstructor
@Slf4j
public class SqsConfig {

    private final SqsProperties sqsProperties;

    /**
     * Creates an SQS client configured with credentials and region.
     * Supports custom endpoint for LocalStack.
     *
     * @return configured SqsClient instance
     */
    @Bean
    public SqsClient sqsClient() {
        log.info("Creating SQS client - region: {}, endpoint: {}",
                sqsProperties.getRegion(),
                sqsProperties.getEndpoint() != null ? sqsProperties.getEndpoint() : "(AWS default)");

        var builder = SqsClient.builder()
                .region(Region.of(sqsProperties.getRegion()));

        // Use custom endpoint for LocalStack
        if (sqsProperties.getEndpoint() != null && !sqsProperties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(sqsProperties.getEndpoint()));
        }

        builder.credentialsProvider(DefaultCredentialsProvider.create());

        return builder.build();
    }
}
