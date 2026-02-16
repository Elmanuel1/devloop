package com.tosspaper.config

import com.tosspaper.models.service.EmailDomainService
import com.tosspaper.models.service.ReceivedMessageService
import com.tosspaper.models.service.EmailApprovalService
import com.tosspaper.models.service.ApprovedSendersManagementService
import com.tosspaper.models.service.SenderNotificationService
import com.tosspaper.models.service.SenderApprovalNotificationService
import com.tosspaper.models.service.EmailMetadataService
import com.tosspaper.models.service.EmailService
import com.mailgun.api.v3.MailgunMessagesApi
import com.tosspaper.aiengine.service.ExtractionService
import com.tosspaper.aiengine.service.ProcessingService
import com.tosspaper.aiengine.service.impl.ReductoProcessingService
import com.tosspaper.aiengine.extractors.DocumentExtractor
import com.tosspaper.supabase.AuthInvitationClient
import com.tosspaper.integrations.temporal.IntegrationScheduleManager
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Specification
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.core.io.ClassPathResource
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import([TestSecurityConfiguration.class, TestAwsConfiguration.class])
abstract class BaseIntegrationTest extends Specification {

    // ==================== Raw external API client mocks ====================
    // Only mock SDK clients / HTTP clients that call external services

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

    // ==================== Email-engine service mocks ====================
    // Implementations are in libs/email-engine (not on test classpath)

    @SpringBean
    EmailDomainService emailDomainService = Mock()
    @SpringBean
    ReceivedMessageService receivedMessageService = Mock()
    @SpringBean
    EmailApprovalService emailApprovalService = Mock()
    @SpringBean
    ApprovedSendersManagementService approvedSendersManagementService = Mock()
    @SpringBean
    SenderNotificationService senderNotificationService = Mock()
    @SpringBean
    SenderApprovalNotificationService senderApprovalNotificationService = Mock()
    @SpringBean
    EmailMetadataService emailMetadataService = Mock()
    @SpringBean
    EmailService emailService = Mock()

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
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)

        registry.add("spring.data.redis.host", redis::getHost)
        registry.add("spring.data.redis.port", redis::getFirstMappedPort)
        registry.add("spring.data.redis.password", () -> "mypass")

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

        // Use local JWKS file for JWT validation
        def jwksFile = new ClassPathResource("jwks.json").getFile().getAbsolutePath()
        registry.add("http.security.jwt.jwk-set-uri", () -> "file://${jwksFile}")
    }

    static Tuple2<String, String> initializeCsrfToken(TestRestTemplate restTemplate) {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/csrf", String.class)
        String setCookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)

        if (setCookieHeader == null) {
            return new Tuple2<>(null, null)
        }

        String xrfTokenHeader = setCookieHeader.split(";")[0].split("=")[1]
        return new Tuple2<>(xrfTokenHeader, setCookieHeader)
    }

    def createAuthHeaders(String csrfToken = null, String cookie = null) {
        HttpHeaders headers = new HttpHeaders()
        if (csrfToken) headers.set("X-XSRF-TOKEN", csrfToken)
        if (cookie) headers.set(HttpHeaders.COOKIE, cookie)
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        return headers
    }

    static HttpEntity<Object> createHttpEntityWithCsrf(Object body, String csrfToken, String cookie) {
        HttpHeaders headers = new HttpHeaders()
        headers.set("X-XSRF-TOKEN", csrfToken)
        headers.set(HttpHeaders.COOKIE, cookie)
        headers.set("Authorization", "Bearer " + TestSecurityConfiguration.getTestToken())
        return new HttpEntity<>(body, headers)
    }
}
