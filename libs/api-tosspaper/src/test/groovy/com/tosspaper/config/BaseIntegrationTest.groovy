package com.tosspaper.config

import com.tosspaper.models.service.EmailDomainService
import com.tosspaper.models.service.ReceivedMessageService
import com.tosspaper.aiengine.service.DocumentMatchService
import com.tosspaper.models.service.EmailApprovalService
import com.tosspaper.models.service.ApprovedSendersManagementService
import com.mailgun.api.v3.MailgunMessagesApi
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import com.tosspaper.models.service.SenderNotificationService
import com.tosspaper.models.service.SenderApprovalNotificationService
import com.tosspaper.models.service.RedisStreamPublisher
import com.tosspaper.models.service.EmailMetadataService
import com.tosspaper.models.service.StorageService
import com.tosspaper.models.service.EmailService
import com.tosspaper.models.service.InvoiceSyncService
import com.tosspaper.models.service.ItemService
import com.tosspaper.models.service.PaymentTermService
import com.tosspaper.models.service.PurchaseOrderLookupService
import com.tosspaper.models.service.PurchaseOrderSyncService
import com.tosspaper.models.service.CompanySyncService
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.IntegrationAccountService
import com.tosspaper.models.service.IntegrationsService
import com.tosspaper.aiengine.service.ExtractionService
import com.tosspaper.aiengine.service.ProcessingService
import com.tosspaper.aiengine.service.impl.ReductoProcessingService
import com.tosspaper.aiengine.extractors.DocumentExtractor
import com.tosspaper.supabase.AuthInvitationClient
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.oauth.OAuthStateService
import com.tosspaper.models.service.CompanyLookupService
import com.tosspaper.integrations.temporal.IntegrationScheduleManager
import com.tosspaper.rbac.CompanyInvitationRepository
import com.tosspaper.rbac.AuthorizedUserRepository
import com.tosspaper.integrations.service.IntegrationConnectionService
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.GenericContainer
import spock.lang.Specification
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.core.io.ClassPathResource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
abstract class BaseIntegrationTest extends Specification {

    @MockBean
    EmailDomainService emailDomainService
    @MockBean
    ReceivedMessageService receivedMessageService
    @MockBean
    DocumentMatchService documentMatchService
    @MockBean
    EmailApprovalService emailApprovalService
    @MockBean
    ApprovedSendersManagementService approvedSendersManagementService
    @MockBean
    MailgunMessagesApi mailgunMessagesApi
    @MockBean
    S3Client s3Client
    @MockBean
    S3Presigner s3Presigner
    @MockBean
    SenderNotificationService senderNotificationService
    @MockBean
    SenderApprovalNotificationService senderApprovalNotificationService
    @MockBean
    RedisStreamPublisher redisStreamPublisher
    @MockBean
    AuthInvitationClient authInvitationClient
    @MockBean
    IntegrationProviderFactory integrationProviderFactory
    @MockBean(name = "oAuthStateServiceImpl")
    OAuthStateService oAuthStateService
    @MockBean
    IntegrationScheduleManager integrationScheduleManager
    @MockBean
    CompanyInvitationRepository companyInvitationRepository
    @MockBean
    IntegrationConnectionService integrationConnectionService
    @MockBean
    EmailMetadataService emailMetadataService
    @MockBean(name = "s3StorageService")
    StorageService s3StorageService
    @MockBean(name = "filesystemStorageService")
    StorageService filesystemStorageService
    @MockBean
    EmailService emailService
    @MockBean
    InvoiceSyncService invoiceSyncService
    @MockBean
    ItemService itemService
    @MockBean
    PaymentTermService paymentTermService
    @MockBean
    PurchaseOrderLookupService purchaseOrderLookupService
    @MockBean
    PurchaseOrderSyncService purchaseOrderSyncService
    @MockBean
    CompanySyncService companySyncService
    @MockBean
    ContactSyncService contactSyncService
    @MockBean
    IntegrationAccountService integrationAccountService
    @MockBean
    IntegrationsService integrationsService
    @MockBean
    ExtractionService extractionService
    @MockBean
    DocumentExtractor documentExtractor
    @MockBean(name = "processingService")
    ProcessingService processingService
    @MockBean(name = "reductoProcessingService")
    ReductoProcessingService reductoProcessingService

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "mypass")

    static {
        postgres.start()
        redis.start()
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