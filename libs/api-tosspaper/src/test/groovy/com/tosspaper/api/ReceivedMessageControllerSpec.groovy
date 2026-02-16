package com.tosspaper.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.domain.AttachmentStatus
import com.tosspaper.models.domain.EmailAttachment
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.paging.Paginated
import com.tosspaper.models.paging.Pagination
import com.tosspaper.models.query.ReceivedMessageQuery
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

import java.time.OffsetDateTime

class ReceivedMessageControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    Long companyId

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID

        dsl.insertInto(Tables.COMPANIES)
            .set(Tables.COMPANIES.ID, companyId)
            .set(Tables.COMPANIES.NAME, "Test Company")
            .set(Tables.COMPANIES.EMAIL, "aribooluwatoba@gmail.com")
            .set(Tables.COMPANIES.ASSIGNED_EMAIL, "inbox@company.com")
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.COMPANIES).where(Tables.COMPANIES.ID.eq(companyId)).execute()
    }

    // ==================== listReceivedMessages ====================

    def "listReceivedMessages returns OK with message list"() {
        given: "service returns messages"
            def message1 = createEmailMessage()
            def message2 = createEmailMessage()
            def serviceResult = new Paginated([message1, message2], new Pagination(1, 20, 1, 2))

            receivedMessageService.listReceivedMessages(_ as ReceivedMessageQuery) >> serviceResult

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling listReceivedMessages"
            def response = restTemplate.exchange(
                "/v1/received_messages?page=1&pageSize=20&status=pending&search=invoice",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains messages"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 2
    }

    def "listReceivedMessages uses default page and pageSize when not provided"() {
        given: "service returns empty result"
            def serviceResult = new Paginated([], new Pagination(1, 20, 1, 0))

            receivedMessageService.listReceivedMessages(_ as ReceivedMessageQuery) >> serviceResult

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling listReceivedMessages with no page/pageSize"
            def response = restTemplate.exchange(
                "/v1/received_messages",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains empty list"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 0
    }

    // ==================== getAttachments ====================

    def "getAttachments returns OK with attachment list"() {
        given: "service returns attachments"
            def messageId = UUID.randomUUID()
            def attachment1 = createEmailAttachment("att-1")
            def attachment2 = createEmailAttachment("att-2")

            receivedMessageService.getAttachmentsByMessageId(messageId) >> [attachment1, attachment2]

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())

        when: "calling getAttachments"
            def response = restTemplate.exchange(
                "/v1/received_messages/${messageId}/attachments",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains attachments"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 2
    }

    def "getAttachments returns empty list when no attachments found"() {
        given: "service returns empty list"
            def messageId = UUID.randomUUID()

            receivedMessageService.getAttachmentsByMessageId(messageId) >> []

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())

        when: "calling getAttachments"
            def response = restTemplate.exchange(
                "/v1/received_messages/${messageId}/attachments",
                HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response contains empty list"
            def body = objectMapper.readValue(response.body, Map)
            body.data.isEmpty()
    }

    // ==================== Helper Methods ====================

    private static EmailMessage createEmailMessage() {
        return EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .subject("Test Subject")
            .bodyHtml("<p>Test</p>")
            .providerTimestamp(OffsetDateTime.now())
            .attachmentsCount(0)
            .build()
    }

    private static EmailAttachment createEmailAttachment(String assignedId) {
        return EmailAttachment.builder()
            .assignedId(assignedId)
            .fileName("test.pdf")
            .sizeBytes(1024L)
            .status(AttachmentStatus.pending)
            .contentType("application/pdf")
            .storageUrl("s3://bucket/key")
            .build()
    }
}
