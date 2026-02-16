package com.tosspaper.emailengine.repository


import com.tosspaper.models.exception.DuplicateException
import com.tosspaper.models.exception.NotFoundException
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired

import static com.tosspaper.models.jooq.Tables.*

class EmailMessageRepositoryTest extends BaseRepositoryTest {

    @Autowired
    EmailMessageRepository messageRepository

    @Autowired
    EmailThreadRepository threadRepository

    @Autowired
    DSLContext dsl

    def setup() {
        // Clean all tables before each test
        dsl.deleteFrom(EMAIL_ATTACHMENT).execute()
        dsl.deleteFrom(EMAIL_MESSAGE).execute()
        dsl.deleteFrom(EMAIL_THREAD).execute()
    }

    def "findById should return message when exists"() {
        given: "a message exists in database"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        when: "finding by id"
        def result = messageRepository.findById(savedMessage.getId())

        then: "message is returned with all fields matching input"
        result.id == savedMessage.getId()
        result.threadId == savedMessage.threadId
        result.provider == message.provider
        result.providerMessageId == message.providerMessageId
        result.inReplyTo == message.inReplyTo
        result.fromAddress == message.fromAddress
        result.toAddress == message.toAddress
        result.cc == message.cc
        result.bcc == message.bcc
        result.subject == message.subject
        result.bodyText == message.bodyText
        result.bodyHtml == message.bodyHtml
        result.headers == message.headers
        result.direction == message.direction
        result.status == message.status
        result.providerTimestamp != null
        result.createdAt != null
    }

    def "findById should throw NotFoundException when message does not exist"() {
        when: "finding non-existent message"
        messageRepository.findById(UUID.randomUUID())

        then: "NotFoundException is thrown"
        thrown(NotFoundException)
    }

    def "findByProviderMessageId should return message when exists"() {
        given: "a message exists in database"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        when: "finding by provider message id"
        def result = messageRepository.findByProviderMessageId("mailgun", "msg-456@mailgun.org")

        then: "message is returned matching input"
        result.isPresent()
        result.get().id == savedMessage.getId()
        result.get().provider == message.provider
        result.get().providerMessageId == message.providerMessageId
        result.get().fromAddress == message.fromAddress
    }

    def "findByProviderMessageId should return empty when message does not exist"() {
        when: "finding non-existent message"
        def result = messageRepository.findByProviderMessageId("mailgun", "nonexistent@mailgun.org")

        then: "empty optional is returned"
        result.isEmpty()
    }

    def "save should save message with all fields"() {
        given: "an existing thread and message data"
        def thread = TestDataFactory.createTestThread()
        def firstMessage = TestDataFactory.createTestMessage()
        def savedFirstMessage = messageRepository.saveThreadAndMessage(thread, firstMessage)

        def replyMessage = TestDataFactory.createReplyMessage(savedFirstMessage.threadId)

        when: "saving reply message"
        def savedMessage = messageRepository.save(replyMessage)

        then: "message is saved with all fields matching input"
        savedMessage.id != null
        savedMessage.threadId == replyMessage.threadId
        savedMessage.provider == replyMessage.provider
        savedMessage.providerMessageId == replyMessage.providerMessageId
        savedMessage.inReplyTo == replyMessage.inReplyTo
        savedMessage.fromAddress == replyMessage.fromAddress
        savedMessage.toAddress == replyMessage.toAddress
        savedMessage.cc == replyMessage.cc
        savedMessage.bcc == replyMessage.bcc
        savedMessage.subject == replyMessage.subject
        savedMessage.bodyText == replyMessage.bodyText
        savedMessage.bodyHtml == replyMessage.bodyHtml
        savedMessage.headers != null
        savedMessage.direction == replyMessage.direction
        savedMessage.status == replyMessage.status
        savedMessage.providerTimestamp != null
        savedMessage.createdAt != null
    }

    def "save should fail on duplicate provider message id"() {
        given: "a message already exists"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        when: "trying to save duplicate message"
        def duplicateMessage = TestDataFactory.createTestMessage() // same provider message id
        duplicateMessage.threadId = savedMessage.threadId
        messageRepository.save(duplicateMessage)

        then: "duplicate key exception is thrown"
        thrown(DuplicateException)
    }

    def "saveThreadAndMessage should save both thread and message atomically"() {
        given: "thread and message data"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()

        when: "saving thread and message"
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        then: "message is returned with generated id and thread id matching input"
        savedMessage.id != null
        savedMessage.threadId != null
        savedMessage.provider == message.provider
        savedMessage.providerMessageId == message.providerMessageId
        savedMessage.fromAddress == message.fromAddress
        savedMessage.toAddress == message.toAddress
        savedMessage.subject == message.subject
        savedMessage.bodyText == message.bodyText
        savedMessage.direction == message.direction
        savedMessage.status == message.status
        savedMessage.createdAt != null

        and: "thread is created in database matching input"
        def savedThread = threadRepository.findById(savedMessage.threadId)
        savedThread.id == savedMessage.threadId
        savedThread.subject == thread.subject
        savedThread.provider == thread.provider
        savedThread.providerThreadId == thread.providerThreadId
        savedThread.messageCount == thread.messageCount
        savedThread.createdAt != null

        and: "message is also retrievable from database matching input"
        def retrievedMessage = messageRepository.findById(savedMessage.id)
        retrievedMessage.id == savedMessage.id
        retrievedMessage.threadId == savedMessage.threadId
        retrievedMessage.provider == message.provider
        retrievedMessage.providerMessageId == message.providerMessageId
        retrievedMessage.fromAddress == message.fromAddress
        retrievedMessage.toAddress == message.toAddress
        retrievedMessage.subject == message.subject
        retrievedMessage.bodyText == message.bodyText
        retrievedMessage.direction == message.direction
        retrievedMessage.status == message.status
        retrievedMessage.providerTimestamp != null
        retrievedMessage.createdAt != null

        and: "message can be found by provider message id"
        def foundByProvider = messageRepository.findByProviderMessageId(message.provider, message.providerMessageId)
        foundByProvider.isPresent()
        foundByProvider.get().id == savedMessage.id
    }

    def "saveThreadAndMessage should upsert on duplicate provider message id"() {
        given: "a thread and message already exist"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def firstSaved = messageRepository.saveThreadAndMessage(thread, message)

        when: "saving again with same provider message id"
        def duplicateThread = TestDataFactory.createTestThread()
        duplicateThread.providerThreadId = "different-thread@mailgun.org"
        def duplicateMessage = TestDataFactory.createTestMessage() // same provider message id
        def secondSaved = messageRepository.saveThreadAndMessage(duplicateThread, duplicateMessage)

        then: "existing message is returned (upsert)"
        secondSaved.id == firstSaved.id
        secondSaved.providerMessageId == firstSaved.providerMessageId
    }

    def "saveThreadAndMessage should upsert thread when provider thread id already exists"() {
        given: "a thread already exists"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def firstSaved = messageRepository.saveThreadAndMessage(thread, message)

        when: "saving with same thread but different message"
        def duplicateThread = TestDataFactory.createTestThread() // same provider thread id
        def differentMessage = TestDataFactory.createTestMessage()
        differentMessage.providerMessageId = "different-msg@mailgun.org"
        def secondSaved = messageRepository.saveThreadAndMessage(duplicateThread, differentMessage)

        then: "message is saved under the existing thread (thread was upserted)"
        secondSaved.id != null
        secondSaved.threadId == firstSaved.threadId
        secondSaved.providerMessageId == "different-msg@mailgun.org"
    }

    def "findByAttachmentId should return message when attachment exists"() {
        given: "a message with attachment"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        and: "an attachment"
        def assignedId = "attach_${UUID.randomUUID().toString().take(8)}"
        dsl.insertInto(EMAIL_ATTACHMENT)
            .set(EMAIL_ATTACHMENT.MESSAGE_ID, savedMessage.id)
            .set(EMAIL_ATTACHMENT.ASSIGNED_ID, assignedId)
            .set(EMAIL_ATTACHMENT.FILE_NAME, "doc.pdf")
            .set(EMAIL_ATTACHMENT.CONTENT_TYPE, "application/pdf")
            .set(EMAIL_ATTACHMENT.SIZE_BYTES, 1024L)
            .set(EMAIL_ATTACHMENT.STORAGE_URL, "s3://bucket/doc.pdf")
            .set(EMAIL_ATTACHMENT.LOCAL_FILE_PATH, "/tmp/test/doc.pdf")
            .set(EMAIL_ATTACHMENT.CHECKSUM, "abc123")
            .set(EMAIL_ATTACHMENT.STATUS, "pending")
            .execute()

        when: "finding by attachment ID"
        def result = messageRepository.findByAttachmentId(assignedId)

        then: "message is returned"
        result.isPresent()
        result.get().id == savedMessage.id
        result.get().fromAddress == message.fromAddress
    }

    def "findByAttachmentId should return empty when attachment does not exist"() {
        when: "finding by non-existent attachment ID"
        def result = messageRepository.findByAttachmentId("non-existent-id")

        then: "empty optional is returned"
        result.isEmpty()
    }

    def "findByQuery should filter by createdDateFrom"() {
        given: "messages with different timestamps"
        def now = java.time.OffsetDateTime.now()
        def oldTimestamp = now.minusDays(5)
        def recentTimestamp = now.minusDays(1)

        def thread1 = TestDataFactory.createTestThread()
        def oldMessage = TestDataFactory.createTestMessage()
        oldMessage.providerTimestamp = oldTimestamp
        messageRepository.saveThreadAndMessage(thread1, oldMessage)

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def recentMessage = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        recentMessage.providerTimestamp = recentTimestamp
        messageRepository.saveThreadAndMessage(thread2, recentMessage)

        and: "query with date filter"
        def dateFrom = now.minusDays(2)
        def query = com.tosspaper.models.query.ReceivedMessageQuery.builder()
            .createdDateFrom(dateFrom)
            .page(1)
            .pageSize(10)
            .build()

        when: "finding by query"
        def result = messageRepository.findByQuery(query)

        then: "only recent message is returned"
        result.data().size() == 1
        result.data()[0].providerTimestamp >= dateFrom
    }

    def "findByQuery should filter by createdDateTo"() {
        given: "messages with different timestamps"
        def now = java.time.OffsetDateTime.now()
        def oldTimestamp = now.minusDays(5)
        def recentTimestamp = now.minusDays(1)

        def thread1 = TestDataFactory.createTestThread()
        def oldMessage = TestDataFactory.createTestMessage()
        oldMessage.providerTimestamp = oldTimestamp
        messageRepository.saveThreadAndMessage(thread1, oldMessage)

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def recentMessage = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        recentMessage.providerTimestamp = recentTimestamp
        messageRepository.saveThreadAndMessage(thread2, recentMessage)

        and: "query with date filter"
        def dateTo = now.minusDays(3)
        def query = com.tosspaper.models.query.ReceivedMessageQuery.builder()
            .createdDateTo(dateTo)
            .page(1)
            .pageSize(10)
            .build()

        when: "finding by query"
        def result = messageRepository.findByQuery(query)

        then: "only old message is returned"
        result.data().size() == 1
        result.data()[0].providerTimestamp <= dateTo
    }

    def "findByQuery should filter by assignedEmail"() {
        given: "messages to different recipients"
        def thread1 = TestDataFactory.createTestThread()
        def message1 = TestDataFactory.createTestMessage()
        message1.toAddress = "assigned@tosspaper.com"
        messageRepository.saveThreadAndMessage(thread1, message1)

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def message2 = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        message2.toAddress = "other@tosspaper.com"
        messageRepository.saveThreadAndMessage(thread2, message2)

        and: "query with assigned email filter"
        def query = com.tosspaper.models.query.ReceivedMessageQuery.builder()
            .assignedEmail("assigned@tosspaper.com")
            .page(1)
            .pageSize(10)
            .build()

        when: "finding by query"
        def result = messageRepository.findByQuery(query)

        then: "only matching message is returned"
        result.data().size() == 1
        result.data()[0].toAddress == "assigned@tosspaper.com"
    }

    def "findByQuery should filter by status"() {
        given: "messages with different statuses"
        def thread1 = TestDataFactory.createTestThread()
        def receivedMessage = TestDataFactory.createTestMessage()
        receivedMessage.status = com.tosspaper.models.enums.MessageStatus.RECEIVED
        messageRepository.saveThreadAndMessage(thread1, receivedMessage)

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def processingMessage = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        processingMessage.status = com.tosspaper.models.enums.MessageStatus.PROCESSING
        messageRepository.saveThreadAndMessage(thread2, processingMessage)

        and: "query with status filter"
        def query = com.tosspaper.models.query.ReceivedMessageQuery.builder()
            .status(com.tosspaper.models.enums.MessageStatus.RECEIVED.getValue())
            .page(1)
            .pageSize(10)
            .build()

        when: "finding by query"
        def result = messageRepository.findByQuery(query)

        then: "only matching messages are returned"
        result.data().size() == 1
        result.data()[0].status == com.tosspaper.models.enums.MessageStatus.RECEIVED
    }

    def "findByQuery should filter by fromEmail"() {
        given: "messages from different senders"
        def thread1 = TestDataFactory.createTestThread()
        def message1 = TestDataFactory.createTestMessage()
        message1.fromAddress = "specific@example.com"
        messageRepository.saveThreadAndMessage(thread1, message1)

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def message2 = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        message2.fromAddress = "other@example.com"
        messageRepository.saveThreadAndMessage(thread2, message2)

        and: "query with from email filter"
        def query = com.tosspaper.models.query.ReceivedMessageQuery.builder()
            .fromEmail("specific@example.com")
            .page(1)
            .pageSize(10)
            .build()

        when: "finding by query"
        def result = messageRepository.findByQuery(query)

        then: "only matching message is returned"
        result.data().size() == 1
        result.data()[0].fromAddress == "specific@example.com"
    }

    def "findByQuery should support pagination"() {
        given: "multiple messages"
        5.times { i ->
            def thread = TestDataFactory.createTestThread("mailgun", "thread${i}@mailgun.org")
            def message = TestDataFactory.createTestMessage("mailgun", "msg${i}@mailgun.org")
            messageRepository.saveThreadAndMessage(thread, message)
        }

        and: "query with pagination"
        def query = com.tosspaper.models.query.ReceivedMessageQuery.builder()
            .page(2)
            .pageSize(2)
            .build()

        when: "finding by query"
        def result = messageRepository.findByQuery(query)

        then: "correct page is returned"
        result.data().size() == 2
        result.pagination().page() == 2
        result.pagination().pageSize() == 2
        result.pagination().totalItems() == 5
        result.pagination().totalPages() == 3
    }

    def "findByQuery should exclude soft-deleted threads"() {
        given: "a message with soft-deleted thread"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        // Soft delete the thread
        dsl.update(EMAIL_THREAD)
            .set(EMAIL_THREAD.DELETED_AT, java.time.OffsetDateTime.now())
            .where(EMAIL_THREAD.ID.eq(savedMessage.threadId))
            .execute()

        and: "an active message"
        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def message2 = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        messageRepository.saveThreadAndMessage(thread2, message2)

        and: "query"
        def query = com.tosspaper.models.query.ReceivedMessageQuery.builder()
            .page(1)
            .pageSize(10)
            .build()

        when: "finding by query"
        def result = messageRepository.findByQuery(query)

        then: "soft-deleted message is excluded"
        result.data().size() == 1
        result.data()[0].providerMessageId != savedMessage.providerMessageId
    }

    def "updateStatus should update message status without expected status check"() {
        given: "an existing message"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        message.status = com.tosspaper.models.enums.MessageStatus.RECEIVED
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        when: "updating status without expected status"
        def updated = messageRepository.updateStatus(
            savedMessage.id,
            null,
            com.tosspaper.models.enums.MessageStatus.PROCESSING
        )

        then: "status is updated"
        updated

        and: "message has new status"
        def found = messageRepository.findById(savedMessage.id)
        found.status == com.tosspaper.models.enums.MessageStatus.PROCESSING
    }

    def "updateStatus should update message status with expected status check"() {
        given: "an existing message"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        message.status = com.tosspaper.models.enums.MessageStatus.RECEIVED
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        when: "updating with correct expected status"
        def updated = messageRepository.updateStatus(
            savedMessage.id,
            com.tosspaper.models.enums.MessageStatus.RECEIVED,
            com.tosspaper.models.enums.MessageStatus.PROCESSING
        )

        then: "status is updated"
        updated

        and: "message has new status"
        def found = messageRepository.findById(savedMessage.id)
        found.status == com.tosspaper.models.enums.MessageStatus.PROCESSING
    }

    def "updateStatus should fail when expected status does not match"() {
        given: "an existing message"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        message.status = com.tosspaper.models.enums.MessageStatus.RECEIVED
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        when: "updating with wrong expected status"
        def updated = messageRepository.updateStatus(
            savedMessage.id,
            com.tosspaper.models.enums.MessageStatus.PROCESSING,
            com.tosspaper.models.enums.MessageStatus.PROCESSED
        )

        then: "update fails"
        !updated

        and: "original status is unchanged"
        def found = messageRepository.findById(savedMessage.id)
        found.status == com.tosspaper.models.enums.MessageStatus.RECEIVED
    }

    def "updateStatus should return false for non-existent message"() {
        when: "updating non-existent message"
        def updated = messageRepository.updateStatus(
            UUID.randomUUID(),
            null,
            com.tosspaper.models.enums.MessageStatus.PROCESSING
        )

        then: "update fails"
        !updated
    }

    def "delete should remove message"() {
        given: "an existing message"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        when: "deleting the message"
        messageRepository.delete(savedMessage.id)

        then: "message is deleted"
        def found = messageRepository.findByProviderMessageId(message.provider, message.providerMessageId)
        found.isEmpty()
    }

    def "delete should not throw exception when message does not exist"() {
        when: "deleting non-existent message"
        messageRepository.delete(UUID.randomUUID())

        then: "no exception is thrown"
        noExceptionThrown()
    }
}
