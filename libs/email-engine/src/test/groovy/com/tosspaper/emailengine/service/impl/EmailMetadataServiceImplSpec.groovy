package com.tosspaper.emailengine.service.impl

import com.tosspaper.emailengine.repository.EmailMessageRepository
import com.tosspaper.models.domain.EmailMessage
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class EmailMetadataServiceImplSpec extends Specification {

    EmailMessageRepository emailMessageRepository = Mock()

    @Subject
    EmailMetadataServiceImpl service

    def setup() {
        service = new EmailMetadataServiceImpl(emailMessageRepository)
    }

    def "should get email metadata by attachment assigned ID"() {
        given:
        def messageId = UUID.randomUUID()
        def threadId = UUID.randomUUID()
        def providerTimestamp = OffsetDateTime.now()

        def emailMessage = EmailMessage.builder()
            .id(messageId)
            .threadId(threadId)
            .companyId(1L)
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .subject("Test Email")
            .providerTimestamp(providerTimestamp)
            .build()

        emailMessageRepository.findByAttachmentId("att-123") >> Optional.of(emailMessage)

        when:
        def result = service.getEmailMetadataByAttachmentId("att-123")

        then:
        result.isPresent()
        def metadata = result.get()
        metadata.companyId == 1L
        metadata.fromAddress == "sender@example.com"
        metadata.toAddress == "inbox@company.com"
        metadata.subject == "Test Email"
        metadata.receivedAt == providerTimestamp
        metadata.emailMessageId == messageId
        metadata.emailThreadId == threadId
    }

    def "should return empty optional when email message not found"() {
        given:
        emailMessageRepository.findByAttachmentId("att-999") >> Optional.empty()

        when:
        def result = service.getEmailMetadataByAttachmentId("att-999")

        then:
        result.isEmpty()
    }

    def "should handle null fields in email message"() {
        given:
        def emailMessage = EmailMessage.builder()
            .id(UUID.randomUUID())
            .companyId(1L)
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .subject(null)
            .providerTimestamp(null)
            .threadId(null)
            .build()

        emailMessageRepository.findByAttachmentId("att-456") >> Optional.of(emailMessage)

        when:
        def result = service.getEmailMetadataByAttachmentId("att-456")

        then:
        result.isPresent()
        def metadata = result.get()
        metadata.subject == null
        metadata.receivedAt == null
        metadata.emailThreadId == null
    }
}
