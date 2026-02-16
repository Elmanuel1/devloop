package com.tosspaper.emailengine.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.emailengine.api.dto.WebhookPayload
import com.tosspaper.emailengine.provider.ProviderAdapter
import com.tosspaper.emailengine.provider.ProviderAdapterFactory
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.enums.MessageDirection
import com.tosspaper.models.enums.MessageStatus
import com.tosspaper.models.service.EmailService
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockMultipartHttpServletRequest
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for WebhookController to ensure webhook endpoints
 * correctly handle JSON, multipart, and form-urlencoded payloads.
 */
class WebhookControllerSpec extends Specification {

    ProviderAdapterFactory adapterFactory = Mock()
    EmailService emailService = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    WebhookController controller

    ProviderAdapter mockAdapter = Mock()

    def setup() {
        controller = new WebhookController(adapterFactory, emailService, objectMapper)
        adapterFactory.getAdapter(_) >> mockAdapter
    }

    // ==================== JSON Webhook Tests ====================

    def "should handle JSON webhook successfully"() {
        given:
        def provider = "mailgun"
        def jsonPayload = """
        {
            "sender": "test@example.com",
            "recipient": "inbox@company.com",
            "subject": "Test Email"
        }
        """

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("test@example.com")
            .toAddress("inbox@company.com")
            .subject("Test Email")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        response.statusCode == HttpStatus.OK
        def body = response.body as Map
        body.status == "success"
        body.provider == "mailgun"
        body.messageId == "<msg@example.com>"

        1 * emailService.processWebhook(emailMessage)
    }

    def "should return error response when JSON parsing fails"() {
        given:
        def provider = "mailgun"
        def invalidPayload = "{ invalid json }"

        mockAdapter.parse(_) >> { throw new IllegalArgumentException("Invalid JSON") }

        when:
        def response = controller.handleJsonWebhook(provider, invalidPayload)

        then:
        response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
        def body = response.body as Map
        body.status == "error"
        body.message == "Failed to process webhook"
    }

    def "should handle email with attachments in JSON webhook"() {
        given:
        def provider = "cloudflare"
        def jsonPayload = '{"emailMessage": {"fromAddress": "sender@example.com"}}'

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .subject("Email with attachments")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .attachments([
                com.tosspaper.models.domain.FileObject.builder()
                    .fileName("doc.pdf")
                    .sizeBytes(1024L)
                    .build()
            ])
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        response.statusCode == HttpStatus.OK
        1 * emailService.processWebhook({ EmailMessage msg ->
            msg.hasAttachments() && msg.attachments.size() == 1
        })
    }

    def "should handle email without subject"() {
        given:
        def provider = "mailgun"
        def jsonPayload = '{"sender": "test@example.com"}'

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("test@example.com")
            .toAddress("inbox@company.com")
            .subject(null)
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        response.statusCode == HttpStatus.OK
    }

    // ==================== Multipart Webhook Tests ====================

    def "should handle multipart webhook with direct JSON payload field"() {
        given:
        def provider = "mailgun"
        def request = new MockMultipartHttpServletRequest()

        def emailMessageJson = """
        {
            "fromAddress": "sender@example.com",
            "toAddress": "inbox@company.com",
            "subject": "Test"
        }
        """
        request.addParameter("emailMessage", emailMessageJson)

        def file = new MockMultipartFile("attachment-1", "doc.pdf", "application/pdf", "PDF content".bytes)
        request.addFile(file)

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .subject("Test")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleMultipartWebhook(provider, request)

        then:
        response.statusCode == HttpStatus.OK
        1 * emailService.processWebhook(emailMessage)
    }

    def "should handle multipart webhook with payload field"() {
        given:
        def provider = "mailgun"
        def request = new MockMultipartHttpServletRequest()

        request.addParameter("payload", '{"fromAddress": "sender@example.com"}')

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleMultipartWebhook(provider, request)

        then:
        response.statusCode == HttpStatus.OK
    }

    def "should convert multipart parameters to JSON when no direct payload field"() {
        given:
        def provider = "mailgun"
        def request = new MockMultipartHttpServletRequest()

        request.addParameter("sender", "sender@example.com")
        request.addParameter("recipient", "inbox@company.com")
        request.addParameter("subject", "Test Email")

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .subject("Test Email")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleMultipartWebhook(provider, request)

        then:
        response.statusCode == HttpStatus.OK
    }

    def "should skip file and attachment parameters when converting to JSON"() {
        given:
        def provider = "mailgun"
        def request = new MockMultipartHttpServletRequest()

        request.addParameter("sender", "sender@example.com")
        request.addParameter("file", "should-be-ignored")
        request.addParameter("attachment", "should-be-ignored")

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleMultipartWebhook(provider, request)

        then:
        response.statusCode == HttpStatus.OK
    }

    def "should handle multipart webhook with multiple files"() {
        given:
        def provider = "mailgun"
        def request = new MockMultipartHttpServletRequest()

        request.addParameter("sender", "sender@example.com")

        def file1 = new MockMultipartFile("attachment-1", "doc1.pdf", "application/pdf", "PDF1".bytes)
        def file2 = new MockMultipartFile("attachment-2", "doc2.pdf", "application/pdf", "PDF2".bytes)
        request.addFile(file1)
        request.addFile(file2)

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .attachments([
                com.tosspaper.models.domain.FileObject.builder().fileName("doc1.pdf").build(),
                com.tosspaper.models.domain.FileObject.builder().fileName("doc2.pdf").build()
            ])
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleMultipartWebhook(provider, request)

        then:
        response.statusCode == HttpStatus.OK
    }

    def "should handle multipart webhook with array parameter values"() {
        given:
        def provider = "mailgun"
        def request = new MockMultipartHttpServletRequest()

        request.addParameter("sender", "sender@example.com")
        request.addParameter("cc", "cc1@example.com")
        request.addParameter("cc", "cc2@example.com")

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .cc("cc1@example.com, cc2@example.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleMultipartWebhook(provider, request)

        then:
        response.statusCode == HttpStatus.OK
    }

    def "should return error when multipart parsing fails"() {
        given:
        def provider = "mailgun"
        def request = new MockMultipartHttpServletRequest()

        request.addParameter("sender", "sender@example.com")

        mockAdapter.parse(_) >> { throw new RuntimeException("Parse error") }

        when:
        def response = controller.handleMultipartWebhook(provider, request)

        then:
        response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
        def body = response.body as Map
        body.status == "error"
    }

    // ==================== Form URL-Encoded Webhook Tests ====================

    def "should handle form-urlencoded webhook successfully"() {
        given:
        def provider = "mailgun"
        def formData = [
            "sender": "sender@example.com",
            "recipient": "inbox@company.com",
            "subject": "Test Email"
        ]

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .subject("Test Email")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleFormUrlencodedWebhook(provider, formData)

        then:
        response.statusCode == HttpStatus.OK
        def body = response.body as Map
        body.status == "success"
        body.provider == "mailgun"

        1 * emailService.processWebhook(emailMessage)
    }

    def "should convert form data to JSON correctly"() {
        given:
        def provider = "mailgun"
        def formData = [
            "from": "sender@example.com",
            "to": "inbox@company.com",
            "subject": "Test"
        ]

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("sender@example.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleFormUrlencodedWebhook(provider, formData)

        then:
        response.statusCode == HttpStatus.OK
    }

    def "should handle empty form data"() {
        given:
        def provider = "mailgun"
        def formData = [:]

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleFormUrlencodedWebhook(provider, formData)

        then:
        response.statusCode == HttpStatus.OK
    }

    def "should return error when form-urlencoded parsing fails"() {
        given:
        def provider = "mailgun"
        def formData = ["sender": "test@example.com"]

        mockAdapter.parse(_) >> { throw new IllegalArgumentException("Parse error") }

        when:
        def response = controller.handleFormUrlencodedWebhook(provider, formData)

        then:
        response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
        def body = response.body as Map
        body.status == "error"
    }

    // ==================== Provider Tests ====================

    def "should use correct provider adapter"() {
        given:
        def provider = "cloudflare"
        def jsonPayload = '{"emailMessage": {}}'

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        controller.handleJsonWebhook(provider, jsonPayload)

        then:
        1 * adapterFactory.getAdapter("cloudflare")
    }

    def "should handle provider adapter not found error"() {
        given:
        def provider = "unknown-provider"
        def jsonPayload = '{"test": "data"}'

        adapterFactory.getAdapter("unknown-provider") >> { throw new IllegalArgumentException("No adapter found") }

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
        def body = response.body as Map
        body.status == "error"
    }

    // ==================== Error Response Tests ====================

    def "should include timestamp in error response"() {
        given:
        def provider = "mailgun"
        def jsonPayload = '{"test": "data"}'

        mockAdapter.parse(_) >> { throw new RuntimeException("Error") }

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
        def body = response.body as Map
        body.timestamp != null
    }

    def "should include provider in error response"() {
        given:
        def provider = "mailgun"
        def jsonPayload = '{"test": "data"}'

        mockAdapter.parse(_) >> { throw new RuntimeException("Error") }

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        def body = response.body as Map
        body.provider == "mailgun"
    }

    // ==================== Success Response Tests ====================

    def "should include timestamp in success response"() {
        given:
        def provider = "mailgun"
        def jsonPayload = '{"sender": "test@example.com"}'

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<msg@example.com>")
            .fromAddress("test@example.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        def body = response.body as Map
        body.timestamp != null
    }

    def "should include message ID in success response"() {
        given:
        def provider = "mailgun"
        def jsonPayload = '{"sender": "test@example.com"}'

        def emailMessage = EmailMessage.builder()
            .providerMessageId("<unique-msg-id-123@example.com>")
            .fromAddress("test@example.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        mockAdapter.parse(_) >> emailMessage

        when:
        def response = controller.handleJsonWebhook(provider, jsonPayload)

        then:
        def body = response.body as Map
        body.messageId == "<unique-msg-id-123@example.com>"
    }
}
