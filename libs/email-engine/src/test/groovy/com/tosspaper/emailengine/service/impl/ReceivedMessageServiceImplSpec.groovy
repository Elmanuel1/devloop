package com.tosspaper.emailengine.service.impl

import com.tosspaper.emailengine.repository.EmailAttachmentRepository
import com.tosspaper.emailengine.repository.EmailMessageRepository
import com.tosspaper.models.domain.EmailAttachment
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.paging.Paginated
import com.tosspaper.models.paging.Pagination
import com.tosspaper.models.query.ReceivedMessageQuery
import spock.lang.Specification
import spock.lang.Subject

class ReceivedMessageServiceImplSpec extends Specification {

    EmailMessageRepository emailMessageRepository = Mock()
    EmailAttachmentRepository emailAttachmentRepository = Mock()

    @Subject
    ReceivedMessageServiceImpl service

    def setup() {
        service = new ReceivedMessageServiceImpl(emailMessageRepository, emailAttachmentRepository)
    }

    def "should list received messages with query"() {
        given:
        def query = ReceivedMessageQuery.builder().assignedEmail("inbox@company.com").page(1).pageSize(10).build()

        def msg1 = EmailMessage.builder().id(UUID.randomUUID()).fromAddress("sender1@example.com").build()
        def msg2 = EmailMessage.builder().id(UUID.randomUUID()).fromAddress("sender2@example.com").build()

        def pagination = new Pagination(1, 10, 1, 2)
        def paginated = new Paginated<EmailMessage>([msg1, msg2], pagination)
        emailMessageRepository.findByQuery(query) >> paginated

        when:
        def result = service.listReceivedMessages(query)

        then:
        result.data().size() == 2
        result.pagination().totalItems() == 2
    }

    def "should get attachments by message ID"() {
        given:
        def messageId = UUID.randomUUID()

        def att1 = EmailAttachment.builder().assignedId("att-1").fileName("doc1.pdf").build()
        def att2 = EmailAttachment.builder().assignedId("att-2").fileName("doc2.pdf").build()

        emailAttachmentRepository.findByMessageId(messageId) >> [att1, att2]

        when:
        def result = service.getAttachmentsByMessageId(messageId)

        then:
        result.size() == 2
        result[0].fileName == "doc1.pdf"
        result[1].fileName == "doc2.pdf"
    }

    def "should return empty list when no attachments found"() {
        given:
        def messageId = UUID.randomUUID()
        emailAttachmentRepository.findByMessageId(messageId) >> []

        when:
        def result = service.getAttachmentsByMessageId(messageId)

        then:
        result.isEmpty()
    }

    def "should get attachment by storage key and company ID"() {
        given:
        def storageKey = "s3://bucket/key"
        def companyId = 1L

        def attachment = EmailAttachment.builder()
            .assignedId("att-1")
            .storageUrl(storageKey)
            .build()

        emailAttachmentRepository.findByStorageKeyAndCompanyId(storageKey, companyId) >> Optional.of(attachment)

        when:
        def result = service.getAttachmentByStorageKey(storageKey, companyId)

        then:
        result.isPresent()
        result.get().assignedId == "att-1"
        result.get().storageUrl == storageKey
    }

    def "should return empty optional when attachment not found by storage key"() {
        given:
        emailAttachmentRepository.findByStorageKeyAndCompanyId("key", 1L) >> Optional.empty()

        when:
        def result = service.getAttachmentByStorageKey("key", 1L)

        then:
        result.isEmpty()
    }
}
