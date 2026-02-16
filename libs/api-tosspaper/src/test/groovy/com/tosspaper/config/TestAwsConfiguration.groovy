package com.tosspaper.config

import com.tosspaper.models.properties.AwsProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * Test configuration that provides real S3Client and S3Presigner beans
 * configured to point at LocalStack. This replaces the @SpringBean mocks
 * that were previously used in BaseIntegrationTest.
 *
 * Needed because ApiTossPaperApplication doesn't include the AwsConfig
 * from services/everything — that config lives in the everything service.
 */
@TestConfiguration
class TestAwsConfiguration {

    @Bean
    S3Client s3Client(AwsProperties awsProperties) {
        def builder = S3Client.builder()
                .region(Region.of(awsProperties.getBucket().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())

        String endpoint = awsProperties.getBucket().getEndpoint()
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()))
        }

        builder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(awsProperties.getBucket().isPathStyleAccess())
                .build())

        return builder.build()
    }

    @Bean
    S3Presigner s3Presigner(AwsProperties awsProperties) {
        def builder = S3Presigner.builder()
                .region(Region.of(awsProperties.getBucket().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(awsProperties.getBucket().isPathStyleAccess())
                        .build())

        String endpoint = awsProperties.getBucket().getEndpoint()
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
        }

        return builder.build()
    }
}
