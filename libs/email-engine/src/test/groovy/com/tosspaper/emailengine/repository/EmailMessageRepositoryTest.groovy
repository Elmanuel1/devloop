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

    def "saveThreadAndMessage should fail on duplicate provider message id"() {
        given: "a thread and message already exist"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        messageRepository.saveThreadAndMessage(thread, message)

        when: "trying to save duplicate message"
        def duplicateThread = TestDataFactory.createTestThread()
        duplicateThread.providerThreadId = "different-thread@mailgun.org"
        def duplicateMessage = TestDataFactory.createTestMessage() // same provider message id
        messageRepository.saveThreadAndMessage(duplicateThread, duplicateMessage)

        then: "duplicate exception is thrown"
        thrown(DuplicateException)
    }

    def "saveThreadAndMessage should fail on duplicate provider thread id"() {
        given: "a thread already exists"
        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        messageRepository.saveThreadAndMessage(thread, message)

        when: "trying to save duplicate thread"
        def duplicateThread = TestDataFactory.createTestThread() // same provider thread id
        def differentMessage = TestDataFactory.createTestMessage()
        differentMessage.providerMessageId = "different-msg@mailgun.org"
        messageRepository.saveThreadAndMessage(duplicateThread, differentMessage)

        then: "duplicate exception is thrown"
        thrown(DuplicateException)
    }
}
