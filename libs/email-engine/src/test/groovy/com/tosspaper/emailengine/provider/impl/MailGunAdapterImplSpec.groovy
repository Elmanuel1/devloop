package com.tosspaper.emailengine.provider.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.tosspaper.emailengine.api.dto.WebhookPayload
import com.tosspaper.models.enums.MessageDirection
import com.tosspaper.models.enums.MessageStatus
import org.springframework.mock.web.MockMultipartFile
import spock.lang.Specification
import spock.lang.Subject

import java.nio.charset.StandardCharsets

/**
 * Tests for MailGunAdapterImpl to ensure Mailgun webhook parsing
 * correctly converts webhook payloads to EmailMessage domain objects.
 *
 * Test payloads are loaded from src/test/resources/mailgun/ to use
 * realistic Mailgun webhook structures.
 */
class MailGunAdapterImplSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())

    @Subject
    MailGunAdapterImpl adapter = new MailGunAdapterImpl(objectMapper)

    // ==================== Provider Name Tests ====================

    def "should return correct provider name"() {
        expect:
        adapter.getProviderName() == "mailgun"
    }

    // ==================== Basic Parsing Tests ====================

    def "should parse basic inbound email without attachments"() {
        given:
        def jsonPayload = loadPayload("inbound-basic.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage != null
        emailMessage.provider == "mailgun"
        emailMessage.providerMessageId == "<CADtJ4eNgLA1234567890qwertyuiop@mail.gmail.com>"
        emailMessage.fromAddress == "vendor@supplier.com"
        emailMessage.toAddress == "invoices@company.example.com"
        emailMessage.subject == "Invoice #12345 - January 2024"
        emailMessage.bodyText.contains("Please find attached invoice #12345")
        emailMessage.bodyHtml.contains("<strong>") || emailMessage.bodyHtml.contains("<p>")
        emailMessage.direction == MessageDirection.INCOMING
        emailMessage.status == MessageStatus.RECEIVED
    }

    def "should extract clean email address from display name format"() {
        given:
        def jsonPayload = loadPayload("inbound-basic.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then: "From header 'John Smith <vendor@supplier.com>' should be cleaned to just email"
        emailMessage.fromAddress == "vendor@supplier.com"
    }

    def "should parse email with CC recipients"() {
        given:
        def jsonPayload = loadPayload("inbound-with-attachment.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.cc == "accounting@acme-corp.com, finance@acme-corp.com"
    }

    def "should parse email with BCC recipients"() {
        given:
        def jsonPayload = loadPayload("inbound-multiple-attachments.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.bcc == "archive@legal-firm.com"
    }

    // ==================== Reply Email Tests ====================

    def "should parse In-Reply-To header for email replies"() {
        given:
        def jsonPayload = loadPayload("inbound-reply.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.inReplyTo == "<CADtJ4eNgLA1234567890qwertyuiop@mail.gmail.com>"
        emailMessage.subject.startsWith("Re:")
    }

    def "should parse reply email with quoted content in body"() {
        given:
        def jsonPayload = loadPayload("inbound-reply.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then: "body-plain contains the full thread including quoted replies"
        emailMessage.bodyText.contains("Thank you for confirming receipt")
        emailMessage.bodyText.contains("On Thu, Jan 11, 2024")
    }

    // ==================== Timestamp Parsing Tests ====================

    def "should parse Unix timestamp correctly"() {
        given:
        def jsonPayload = loadPayload("inbound-basic.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then: "timestamp 1704998400 = 2024-01-11 19:00:00 UTC"
        emailMessage.providerTimestamp != null
        emailMessage.providerTimestamp.year == 2024
        emailMessage.providerTimestamp.monthValue == 1
        emailMessage.providerTimestamp.dayOfMonth == 11
    }

    // ==================== Headers Parsing Tests ====================

    def "should parse message headers as JSON string"() {
        given:
        def jsonPayload = loadPayload("inbound-basic.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.headers != null
        emailMessage.headers.contains("Received")
        emailMessage.headers.contains("DKIM-Signature")
    }

    // ==================== Attachment Parsing Tests ====================

    def "should parse email with no attachments"() {
        given:
        def jsonPayload = loadPayload("inbound-basic.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments != null
        emailMessage.attachments.isEmpty()
    }

    def "should parse email with single PDF attachment"() {
        given:
        def jsonPayload = loadPayload("inbound-with-attachment.json")
        def pdfContent = loadBinaryResource("sample-invoice.pdf")
        def pdfFile = new MockMultipartFile(
            "attachment-1",
            "INV-2024-0042.pdf",
            "application/pdf",
            pdfContent
        )
        def webhookPayload = WebhookPayload.fromMultipart(jsonPayload, ["attachment-1": pdfFile])

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments != null
        emailMessage.attachments.size() == 1

        and: "attachment has correct properties"
        def attachment = emailMessage.attachments[0]
        attachment.fileName == "INV-2024-0042.pdf"
        attachment.contentType == "application/pdf"
        attachment.content == pdfContent
        attachment.sizeBytes == pdfContent.length
    }

    def "should parse email with multiple attachments"() {
        given:
        def jsonPayload = loadPayload("inbound-multiple-attachments.json")
        def pdf1 = new MockMultipartFile("attachment-1", "MSA_ProjectAlpha_v2.1.pdf", "application/pdf", "PDF1".bytes)
        def pdf2 = new MockMultipartFile("attachment-2", "SOW_ProjectAlpha_2024.pdf", "application/pdf", "PDF2".bytes)
        def pdf3 = new MockMultipartFile("attachment-3", "COI_LegalFirm_2024.pdf", "application/pdf", "PDF3".bytes)
        def webhookPayload = WebhookPayload.fromMultipart(jsonPayload, [
            "attachment-1": pdf1,
            "attachment-2": pdf2,
            "attachment-3": pdf3
        ])

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments.size() == 3

        and: "all attachments are parsed correctly"
        emailMessage.attachments.collect { it.fileName }.sort() == [
            "COI_LegalFirm_2024.pdf",
            "MSA_ProjectAlpha_v2.1.pdf",
            "SOW_ProjectAlpha_2024.pdf"
        ]
    }

    def "should generate unique assigned IDs for attachments"() {
        given:
        def jsonPayload = loadPayload("inbound-multiple-attachments.json")
        def file1 = new MockMultipartFile("attachment-1", "doc1.pdf", "application/pdf", "Doc1".bytes)
        def file2 = new MockMultipartFile("attachment-2", "doc2.pdf", "application/pdf", "Doc2".bytes)
        def file3 = new MockMultipartFile("attachment-3", "doc3.pdf", "application/pdf", "Doc3".bytes)
        def webhookPayload = WebhookPayload.fromMultipart(jsonPayload, [
            "attachment-1": file1,
            "attachment-2": file2,
            "attachment-3": file3
        ])

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then: "each attachment should have a unique assigned ID starting with 'mg-'"
        def assignedIds = emailMessage.attachments.collect { it.assignedId }
        assignedIds.every { it.startsWith("mg-") }
        assignedIds.unique().size() == 3
    }

    def "should calculate SHA-256 checksum for attachments"() {
        given:
        def jsonPayload = loadPayload("inbound-with-attachment.json")
        def content = "Test content for checksum verification".bytes
        def file = new MockMultipartFile("attachment-1", "test.pdf", "application/pdf", content)
        def webhookPayload = WebhookPayload.fromMultipart(jsonPayload, ["attachment-1": file])

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def attachment = emailMessage.attachments[0]
        attachment.checksum != null
        attachment.checksum.length() == 64 // SHA-256 produces 64 hex characters
    }

    def "should skip empty attachments"() {
        given:
        def jsonPayload = loadPayload("inbound-multiple-attachments.json")
        def validFile = new MockMultipartFile("attachment-1", "valid.pdf", "application/pdf", "Content".bytes)
        def emptyFile = new MockMultipartFile("attachment-2", "empty.pdf", "application/pdf", new byte[0])
        def anotherValid = new MockMultipartFile("attachment-3", "another.pdf", "application/pdf", "More".bytes)
        def webhookPayload = WebhookPayload.fromMultipart(jsonPayload, [
            "attachment-1": validFile,
            "attachment-2": emptyFile,
            "attachment-3": anotherValid
        ])

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then: "should skip empty file"
        emailMessage.attachments.size() == 2
        emailMessage.attachments.collect { it.fileName }.sort() == ["another.pdf", "valid.pdf"]
    }

    // ==================== Metadata Tests ====================

    def "should include metadata in file objects"() {
        given:
        def jsonPayload = loadPayload("inbound-with-attachment.json")
        def file = new MockMultipartFile("attachment-1", "doc.pdf", "application/pdf", "Content".bytes)
        def webhookPayload = WebhookPayload.fromMultipart(jsonPayload, ["attachment-1": file])

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def attachment = emailMessage.attachments[0]
        attachment.metadata != null
        attachment.metadata["provider-message-id"] == "<billing.2024.01.042@vendor-services.com>"
        attachment.metadata["from-address"] == "billing@vendor-services.com"
        attachment.metadata["to-address"] == "ap@acme-corp.com"
    }

    // ==================== Key Generation Tests ====================

    def "should generate storage key for attachments"() {
        given:
        def jsonPayload = loadPayload("inbound-with-attachment.json")
        def file = new MockMultipartFile("attachment-1", "invoice.pdf", "application/pdf", "Content".bytes)
        def webhookPayload = WebhookPayload.fromMultipart(jsonPayload, ["attachment-1": file])

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def attachment = emailMessage.attachments[0]
        attachment.key != null
        attachment.key.contains("ap@acme_corp.com")
        attachment.key.contains("billing@vendor_services.com")
    }

    // ==================== Error Handling Tests ====================

    def "should throw exception for invalid JSON payload"() {
        given:
        def invalidJson = "{ invalid json }"
        def webhookPayload = WebhookPayload.fromJson(invalidJson)

        when:
        adapter.parse(webhookPayload)

        then:
        thrown(IllegalArgumentException)
    }

    def "should throw UnsupportedOperationException for validateSignature"() {
        when:
        adapter.validateSignature("payload", "signature", "secret")

        then:
        thrown(UnsupportedOperationException)
    }

    // ==================== Business Email Scenarios ====================

    def "should handle typical invoice email from vendor"() {
        given:
        def jsonPayload = loadPayload("inbound-with-attachment.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then: "invoice email has expected business content"
        emailMessage.subject.contains("INV-")
        emailMessage.bodyText.contains("Invoice")
        emailMessage.bodyText.contains("Amount Due")
        emailMessage.bodyText.contains("Payment Terms")
    }

    def "should handle contract documents email"() {
        given:
        def jsonPayload = loadPayload("inbound-multiple-attachments.json")
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then: "contract email has expected legal content"
        emailMessage.subject.contains("Contract")
        emailMessage.bodyText.contains("Master Services Agreement")
        emailMessage.bodyText.contains("Statement of Work")
    }

    // ==================== Helper Methods ====================

    private String loadPayload(String filename) {
        def resource = getClass().getResourceAsStream("/mailgun/${filename}")
        if (resource == null) {
            throw new IllegalStateException("Test resource not found: /mailgun/${filename}")
        }
        return resource.text
    }

    private byte[] loadBinaryResource(String filename) {
        def resource = getClass().getResourceAsStream("/mailgun/${filename}")
        if (resource == null) {
            // Return dummy PDF content if resource doesn't exist
            return "%PDF-1.4 dummy content".bytes
        }
        return resource.bytes
    }
}
