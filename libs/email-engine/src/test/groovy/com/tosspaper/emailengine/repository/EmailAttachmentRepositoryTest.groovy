package com.tosspaper.emailengine.repository

import com.tosspaper.models.domain.EmailAttachment
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired

import static com.tosspaper.models.jooq.Tables.*


class EmailAttachmentRepositoryTest extends BaseRepositoryTest {

    @Autowired
    EmailAttachmentRepository attachmentRepository

    @Autowired
    EmailThreadRepository threadRepository

    @Autowired
    EmailMessageRepository messageRepository

    @Autowired
    DSLContext dsl

    def setup() {
        // Clean all tables before each test
        dsl.deleteFrom(EMAIL_ATTACHMENT).execute()
        dsl.deleteFrom(EMAIL_MESSAGE).execute()
        dsl.deleteFrom(EMAIL_THREAD).execute()
    }

    def "saveAll should save multiple attachments with all fields"() {
        given: "an existing message and attachment data"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachments = [
            TestDataFactory.createTestAttachment(savedMessage.getId(), "document.pdf", "application/pdf", 1024L),
            TestDataFactory.createTestAttachment(savedMessage.getId(), "image.jpg", "image/jpeg", 2048L),
            TestDataFactory.createTestAttachment(savedMessage.getId(), "text.txt", "text/plain", 512L)
        ]

        when: "saving all attachments"
        def savedAttachments = attachmentRepository.saveAll(attachments)

        then: "all attachments are saved with generated ids and fields matching input"
        savedAttachments.size() == attachments.size()

        savedAttachments[0].id != null
        savedAttachments[0].messageId == attachments[0].messageId
        savedAttachments[0].fileName == attachments[0].fileName
        savedAttachments[0].contentType == attachments[0].contentType
        savedAttachments[0].sizeBytes == attachments[0].sizeBytes
        savedAttachments[0].storageUrl == attachments[0].storageUrl
        savedAttachments[0].checksum == attachments[0].checksum
        savedAttachments[0].createdAt != null

        savedAttachments[1].id != null
        savedAttachments[1].messageId == attachments[1].messageId
        savedAttachments[1].fileName == attachments[1].fileName
        savedAttachments[1].contentType == attachments[1].contentType
        savedAttachments[1].sizeBytes == attachments[1].sizeBytes
        savedAttachments[1].storageUrl == attachments[1].storageUrl
        savedAttachments[1].checksum == attachments[1].checksum
        savedAttachments[1].createdAt != null

        savedAttachments[2].id != null
        savedAttachments[2].messageId == attachments[2].messageId
        savedAttachments[2].fileName == attachments[2].fileName
        savedAttachments[2].contentType == attachments[2].contentType
        savedAttachments[2].sizeBytes == attachments[2].sizeBytes
        savedAttachments[2].storageUrl == attachments[2].storageUrl
        savedAttachments[2].checksum == attachments[2].checksum
        savedAttachments[2].createdAt != null

        and: "all attachments have unique ids"
        def ids = savedAttachments.collect { it.id }
        ids.unique().size() == 3
    }

    def "saveAll should handle empty list"() {
        when: "saving empty list"
        def result = attachmentRepository.saveAll([])

        then: "empty list is returned"
        result.isEmpty()
    }

    def "saveAll should handle single attachment"() {
        given: "an existing message and single attachment"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "single.pdf", "application/pdf", 1024L)

        when: "saving single attachment"
        def savedAttachments = attachmentRepository.saveAll([attachment])

        then: "attachment is saved correctly matching input"
        savedAttachments.size() == 1
        savedAttachments[0].id != null
        savedAttachments[0].messageId == attachment.messageId
        savedAttachments[0].fileName == attachment.fileName
        savedAttachments[0].contentType == attachment.contentType
        savedAttachments[0].sizeBytes == attachment.sizeBytes
        savedAttachments[0].storageUrl == attachment.storageUrl
        savedAttachments[0].checksum == attachment.checksum
        savedAttachments[0].createdAt != null
    }


    def "saveAll should handle attachments with all required fields"() {
        given: "an existing message and attachment with all required data"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = EmailAttachment.builder()
            .messageId(savedMessage.getId())
            .fileName("complete.txt")
            .contentType("text/plain")
            .sizeBytes(100L)
            .storageUrl("s3://bucket/complete.txt")
            .checksum("sha256hash")
            .build()

        when: "saving attachment with all fields"
        def savedAttachments = attachmentRepository.saveAll([attachment])

        then: "attachment is saved with fields matching input"
        savedAttachments.size() == 1
        savedAttachments[0].id != null
        savedAttachments[0].messageId == attachment.messageId
        savedAttachments[0].fileName == attachment.fileName
        savedAttachments[0].contentType == attachment.contentType
        savedAttachments[0].sizeBytes == attachment.sizeBytes
        savedAttachments[0].storageUrl == attachment.storageUrl
        savedAttachments[0].checksum == attachment.checksum
        savedAttachments[0].createdAt != null
    }

}