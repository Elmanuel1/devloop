package com.tosspaper.everything

import com.mailgun.api.v3.MailgunMessagesApi
import com.tosspaper.aiengine.extractors.DocumentExtractor
import com.tosspaper.aiengine.service.ExtractionService
import com.tosspaper.aiengine.service.ProcessingService
import com.tosspaper.aiengine.service.impl.ReductoProcessingService
import com.tosspaper.everything.config.TestSecurityConfiguration
import com.tosspaper.integrations.temporal.IntegrationScheduleManager
import com.tosspaper.supabase.AuthInvitationClient
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Specification
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
class EverythingApplicationTest extends Specification {

    // ==================== External API client mocks ====================
    @SpringBean
    MailgunMessagesApi mailgunMessagesApi = Mock()
    @SpringBean
    AuthInvitationClient authInvitationClient = Mock()
    @SpringBean
    IntegrationScheduleManager integrationScheduleManager = Mock()
    @SpringBean
    ExtractionService extractionService = Mock()
    @SpringBean
    DocumentExtractor documentExtractor = Mock()
    @SpringBean
    @Qualifier("processingService")
    ProcessingService processingService = Mock()
    @SpringBean
    @Qualifier("reductoProcessingService")
    ReductoProcessingService reductoProcessingService = Mock()

    // ==================== Testcontainers ====================
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("tosspaper")
            .withUsername("postgres")
            .withPassword("postgres")

    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "mypass")

    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.0"))
            .withServices(LocalStackContainer.Service.S3)

    static {
        // Disable SSM Parameter Store import BEFORE Spring context starts
        System.setProperty("spring.config.import", "")
        System.setProperty("spring.cloud.aws.parameterstore.enabled", "false")

        postgres.start()
        redis.start()
        localstack.start()

        // Set AWS credentials as system properties for DefaultCredentialsProvider
        System.setProperty("aws.accessKeyId", localstack.getAccessKey())
        System.setProperty("aws.secretAccessKey", localstack.getSecretKey())

        // Create S3 bucket
        def s3 = S3Client.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build()
        s3.createBucket(CreateBucketRequest.builder().bucket("tosspaper-email-attachments").build())
        s3.close()
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Database
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)

        // Redis
        registry.add("spring.data.redis.host", redis::getHost)
        registry.add("spring.data.redis.port", redis::getFirstMappedPort)
        registry.add("spring.data.redis.password", () -> "mypass")

        // Redisson
        registry.add("redisson.config", () -> """
            singleServerConfig:
              address: "redis://${redis.getHost()}:${redis.getFirstMappedPort()}"
              password: "mypass"
              database: 0
        """)

        // S3 via LocalStack
        registry.add("aws.bucket.endpoint", () -> localstack.getEndpointOverride(LocalStackContainer.Service.S3).toString())
        registry.add("aws.bucket.region", localstack::getRegion)
        registry.add("aws.bucket.path-style-access", () -> "true")
        registry.add("aws.bucket.name", () -> "tosspaper-email-attachments")

        // JWT
        def jwksFile = new ClassPathResource("jwks.json").getFile().getAbsolutePath()
        registry.add("http.security.jwt.jwk-set-uri", () -> "file://${jwksFile}")
    }

    def "context loads"() {
        expect:
        true
    }
}
