package com.tosspaper.integrations.mailgun

import com.mailgun.api.v3.MailgunMessagesApi
import com.mailgun.model.message.Message
import com.mailgun.model.message.MessageResponse
import com.tosspaper.models.config.MailgunProperties
import com.tosspaper.models.email.EmailRequest
import feign.FeignException
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive tests for MailgunEmailProvider.
 * Tests email sending via Mailgun API.
 */
class MailgunEmailProviderSpec extends Specification {

    MailgunMessagesApi mailgunMessagesApi = Mock()
    MailgunProperties mailgunProperties = new MailgunProperties()

    @Subject
    MailgunEmailProvider provider

    def setup() {
        mailgunProperties.domain = "mg.example.com"
        mailgunProperties.fromEmail = "noreply@example.com"

        provider = new MailgunEmailProvider(
            mailgunMessagesApi,
            mailgunProperties
        )
    }

    def "sendEmail should send email with text body successfully"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient@example.com",
            "Test Subject",
            "Text body content",
            null,
            []
        )

        def response = MessageResponse.builder()
            .message("Email sent successfully")
            .build()

        when:
        def result = provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", _) >> response
        result == "Email sent successfully"
    }

    def "sendEmail should send email with HTML body"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient@example.com",
            "HTML Subject",
            null,
            "<html><body>HTML content</body></html>",
            []
        )

        def response = MessageResponse.builder()
            .message("Email sent")
            .build()

        when:
        def result = provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", _) >> response
        result == "Email sent"
    }

    def "sendEmail should send email with both text and HTML bodies"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient@example.com",
            "Multipart Subject",
            "Text version",
            "<html>HTML version</html>",
            []
        )

        def response = GroovyMock(MessageResponse) {
            getMessage() >> "Email sent"
        }

        when:
        provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", _) >> response
    }

    def "sendEmail should send to comma-separated recipients"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient1@example.com, recipient2@example.com, recipient3@example.com",
            "Subject",
            "Body",
            null,
            []
        )

        def response = GroovyMock(MessageResponse) {
            getMessage() >> "Email sent"
        }

        when:
        provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", _) >> response
    }

    def "sendEmail should throw exception on authentication failure"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient@example.com",
            "Subject",
            "Body",
            null,
            []
        )

        when:
        provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage(_, _) >> { throw Mock(FeignException.Unauthorized) }
        def ex = thrown(RuntimeException)
        ex.message.contains("Mailgun authentication failed")
    }

    def "sendEmail should throw exception on general failure"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient@example.com",
            "Subject",
            "Body",
            null,
            []
        )

        when:
        provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage(_, _) >> { throw new RuntimeException("Network error") }
        def ex = thrown(RuntimeException)
        ex.message.contains("Failed to send email via Mailgun")
    }

    def "sendEmail should handle null text body"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient@example.com",
            "Subject",
            null,
            "<html>HTML only</html>",
            []
        )

        def response = GroovyMock(MessageResponse) {
            getMessage() >> "Email sent"
        }

        when:
        provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", _) >> response
        notThrown(NullPointerException)
    }

    def "sendEmail should handle null HTML body"() {
        given:
        def request = new EmailRequest(
            "sender@example.com",
            "recipient@example.com",
            "Subject",
            "Text only",
            null,
            []
        )

        def response = GroovyMock(MessageResponse) {
            getMessage() >> "Email sent"
        }

        when:
        provider.sendEmail(request)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", _) >> response
        notThrown(NullPointerException)
    }
}
