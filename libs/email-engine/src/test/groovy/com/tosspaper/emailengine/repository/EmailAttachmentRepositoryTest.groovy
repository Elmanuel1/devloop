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
            .assignedId("test_${UUID.randomUUID().toString().take(8)}")
            .fileName("complete.txt")
            .contentType("text/plain")
            .sizeBytes(100L)
            .storageUrl("s3://bucket/complete.txt")
            .localFilePath("/tmp/test/complete.txt")
            .checksum("sha256hash")
            .status(com.tosspaper.models.domain.AttachmentStatus.pending)
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

    def "updateStatusToProcessing should increment attempts and update status"() {
        given: "an existing attachment"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        def savedAttachments = attachmentRepository.saveAll([attachment])
        def assignedId = savedAttachments[0].assignedId

        when: "updating status to processing"
        def updated = attachmentRepository.updateStatusToProcessing(assignedId)

        then: "status is updated and attempts are incremented"
        updated.assignedId == assignedId
        updated.status == com.tosspaper.models.domain.AttachmentStatus.processing
        updated.attempts == 1
    }

    def "updateStatusToProcessing should increment attempts multiple times"() {
        given: "an existing attachment"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        def savedAttachments = attachmentRepository.saveAll([attachment])
        def assignedId = savedAttachments[0].assignedId

        when: "updating status to processing multiple times"
        def updated1 = attachmentRepository.updateStatusToProcessing(assignedId)
        def updated2 = attachmentRepository.updateStatusToProcessing(assignedId)
        def updated3 = attachmentRepository.updateStatusToProcessing(assignedId)

        then: "attempts are correctly incremented"
        updated1.attempts == 1
        updated2.attempts == 2
        updated3.attempts == 3
    }

    def "findByStatus should return attachments with matching status"() {
        given: "attachments with different statuses"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def pending1 = TestDataFactory.createTestAttachment(savedMessage.getId(), "pending1.pdf", "application/pdf", 1024L)
        def pending2 = TestDataFactory.createTestAttachment(savedMessage.getId(), "pending2.pdf", "application/pdf", 2048L)
        attachmentRepository.saveAll([pending1, pending2])

        // Create a processing attachment
        def processing = TestDataFactory.createTestAttachment(savedMessage.getId(), "processing.pdf", "application/pdf", 512L)
        def saved = attachmentRepository.saveAll([processing])
        attachmentRepository.updateStatusToProcessing(saved[0].assignedId)

        when: "finding by pending status"
        def result = attachmentRepository.findByStatus(com.tosspaper.models.domain.AttachmentStatus.pending)

        then: "only pending attachments are returned"
        result.size() == 2
        result.every { it.status == com.tosspaper.models.domain.AttachmentStatus.pending }
        result*.fileName.containsAll(["pending1.pdf", "pending2.pdf"])
    }

    def "findByStatus should exclude soft-deleted threads"() {
        given: "attachments with soft-deleted thread"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        attachmentRepository.saveAll([attachment])

        // Soft delete the thread
        dsl.update(EMAIL_THREAD)
            .set(EMAIL_THREAD.DELETED_AT, java.time.OffsetDateTime.now())
            .where(EMAIL_THREAD.ID.eq(savedMessage.threadId))
            .execute()

        when: "finding by status"
        def result = attachmentRepository.findByStatus(com.tosspaper.models.domain.AttachmentStatus.pending)

        then: "soft-deleted attachments are excluded"
        result.isEmpty()
    }

    def "findByAssignedId should return attachment when it exists"() {
        given: "an existing attachment"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        def saved = attachmentRepository.saveAll([attachment])
        def assignedId = saved[0].assignedId

        when: "finding by assigned ID"
        def result = attachmentRepository.findByAssignedId(assignedId)

        then: "attachment is returned"
        result.isPresent()
        result.get().assignedId == assignedId
        result.get().fileName == "doc.pdf"
    }

    def "findByAssignedId should return empty when attachment does not exist"() {
        when: "finding non-existent attachment"
        def result = attachmentRepository.findByAssignedId("non-existent-id")

        then: "empty optional is returned"
        result.isEmpty()
    }

    def "updateStatus should update attachment status and metadata"() {
        given: "an existing attachment in processing status"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        def saved = attachmentRepository.saveAll([attachment])
        def processing = attachmentRepository.updateStatusToProcessing(saved[0].assignedId)

        and: "updated attachment data"
        processing.status = com.tosspaper.models.domain.AttachmentStatus.uploaded
        processing.storageUrl = "s3://new-bucket/doc.pdf"
        processing.region = "us-east-1"
        processing.endpoint = "https://s3.amazonaws.com"
        processing.metadata = ["key": "value", "uploaded_by": "system"]

        when: "updating status"
        attachmentRepository.updateStatus(processing, com.tosspaper.models.domain.AttachmentStatus.processing)

        then: "attachment is updated"
        def found = attachmentRepository.findByAssignedId(processing.assignedId)
        found.isPresent()
        found.get().status == com.tosspaper.models.domain.AttachmentStatus.uploaded
        found.get().storageUrl == "s3://new-bucket/doc.pdf"
        found.get().region == "us-east-1"
        found.get().endpoint == "https://s3.amazonaws.com"
        found.get().metadata.key == "value"
        found.get().metadata.uploaded_by == "system"
    }

    def "updateStatus should fail when expected status does not match"() {
        given: "an existing attachment in pending status"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        def saved = attachmentRepository.saveAll([attachment])

        and: "update with wrong expected status"
        saved[0].status = com.tosspaper.models.domain.AttachmentStatus.uploaded

        when: "updating with wrong expected status"
        attachmentRepository.updateStatus(saved[0], com.tosspaper.models.domain.AttachmentStatus.processing)

        then: "NotFoundException is thrown"
        thrown(com.tosspaper.models.exception.NotFoundException)
    }

    def "findByMessageId should return all attachments for a message"() {
        given: "a message with multiple attachments"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachments = [
            TestDataFactory.createTestAttachment(savedMessage.getId(), "doc1.pdf", "application/pdf", 1024L),
            TestDataFactory.createTestAttachment(savedMessage.getId(), "doc2.pdf", "application/pdf", 2048L),
            TestDataFactory.createTestAttachment(savedMessage.getId(), "image.jpg", "image/jpeg", 512L)
        ]
        attachmentRepository.saveAll(attachments)

        when: "finding by message ID"
        def result = attachmentRepository.findByMessageId(savedMessage.getId())

        then: "all attachments are returned"
        result.size() == 3
        result*.fileName.containsAll(["doc1.pdf", "doc2.pdf", "image.jpg"])
    }

    def "findByMessageId should return empty list when no attachments exist"() {
        when: "finding attachments for message with no attachments"
        def result = attachmentRepository.findByMessageId(UUID.randomUUID())

        then: "empty list is returned"
        result.isEmpty()
    }

    def "findByDomain should return attachments from messages with matching domain"() {
        given: "messages from different domains"
        def thread1 = TestDataFactory.createTestThread()
        def message1 = TestDataFactory.createTestMessage()
        message1.fromAddress = "sender1@example.com"
        def savedMessage1 = messageRepository.saveThreadAndMessage(thread1, message1)

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def message2 = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        message2.fromAddress = "sender2@example.com"
        def savedMessage2 = messageRepository.saveThreadAndMessage(thread2, message2)

        def thread3 = TestDataFactory.createTestThread("mailgun", "thread3@mailgun.org")
        def message3 = TestDataFactory.createTestMessage("mailgun", "msg3@mailgun.org")
        message3.fromAddress = "sender@different.com"
        def savedMessage3 = messageRepository.saveThreadAndMessage(thread3, message3)

        attachmentRepository.saveAll([
            TestDataFactory.createTestAttachment(savedMessage1.getId(), "doc1.pdf", "application/pdf", 1024L),
            TestDataFactory.createTestAttachment(savedMessage2.getId(), "doc2.pdf", "application/pdf", 2048L),
            TestDataFactory.createTestAttachment(savedMessage3.getId(), "doc3.pdf", "application/pdf", 512L)
        ])

        when: "finding by domain"
        def result = attachmentRepository.findByDomain("example.com")

        then: "only attachments from matching domain are returned"
        result.size() == 2
        result*.fileName.containsAll(["doc1.pdf", "doc2.pdf"])
    }

    def "findByEmail should return attachments from messages with matching email"() {
        given: "messages from different senders"
        def thread1 = TestDataFactory.createTestThread()
        def message1 = TestDataFactory.createTestMessage()
        message1.fromAddress = "specific@example.com"
        def savedMessage1 = messageRepository.saveThreadAndMessage(thread1, message1)

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def message2 = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        message2.fromAddress = "other@example.com"
        def savedMessage2 = messageRepository.saveThreadAndMessage(thread2, message2)

        attachmentRepository.saveAll([
            TestDataFactory.createTestAttachment(savedMessage1.getId(), "doc1.pdf", "application/pdf", 1024L),
            TestDataFactory.createTestAttachment(savedMessage1.getId(), "doc2.pdf", "application/pdf", 2048L),
            TestDataFactory.createTestAttachment(savedMessage2.getId(), "doc3.pdf", "application/pdf", 512L)
        ])

        when: "finding by email"
        def result = attachmentRepository.findByEmail("specific@example.com")

        then: "only attachments from matching email are returned"
        result.size() == 2
        result*.fileName.containsAll(["doc1.pdf", "doc2.pdf"])
    }

    def "findByStorageKeyAndCompanyId should return attachment when it exists"() {
        given: "an existing attachment"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        def saved = attachmentRepository.saveAll([attachment])
        def storageUrl = saved[0].storageUrl

        when: "finding by storage key and company ID"
        def result = attachmentRepository.findByStorageKeyAndCompanyId(storageUrl, TestDataFactory.TEST_COMPANY_ID)

        then: "attachment is returned"
        result.isPresent()
        result.get().storageUrl == storageUrl
        result.get().fileName == "doc.pdf"
    }

    def "findByStorageKeyAndCompanyId should return empty when company does not match"() {
        given: "an existing attachment"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        def attachment = TestDataFactory.createTestAttachment(savedMessage.getId(), "doc.pdf", "application/pdf", 1024L)
        def saved = attachmentRepository.saveAll([attachment])
        def storageUrl = saved[0].storageUrl

        when: "finding with wrong company ID"
        def result = attachmentRepository.findByStorageKeyAndCompanyId(storageUrl, 999L)

        then: "empty optional is returned"
        result.isEmpty()
    }

    def "findByStorageKeyAndCompanyId should return empty when storage key does not exist"() {
        when: "finding non-existent storage key"
        def result = attachmentRepository.findByStorageKeyAndCompanyId("s3://non-existent", TestDataFactory.TEST_COMPANY_ID)

        then: "empty optional is returned"
        result.isEmpty()
    }

}