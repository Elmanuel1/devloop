package com.tosspaper.emailengine.repository


import com.tosspaper.models.exception.NotFoundException
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired

import static com.tosspaper.models.jooq.Tables.*


class EmailThreadRepositoryTest extends BaseRepositoryTest {

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

    def "findById should return thread when exists"() {
        given: "a thread exists in database"
        def thread = TestDataFactory.createTestThread()
        def savedThread = messageRepository.saveThreadAndMessage(thread, TestDataFactory.createTestMessage())

        when: "finding by id"
        def result = threadRepository.findById(savedThread.getThreadId())

        then: "thread is returned with all fields matching input"
        result.id == savedThread.getThreadId()
        result.subject == thread.subject
        result.provider == thread.provider
        result.providerThreadId == thread.providerThreadId
        result.messageCount == thread.messageCount
        result.createdAt != null
        result.lastUpdatedAt == null
    }

    def "findById should throw NotFoundException when thread does not exist"() {
        when: "finding non-existent thread"
        threadRepository.findById(UUID.randomUUID())

        then: "NotFoundException is thrown"
        thrown(NotFoundException)
    }

    def "findByProviderThreadId should return thread when exists"() {
        given: "a thread exists in database"
        def thread = TestDataFactory.createTestThread()
        def savedThread = messageRepository.saveThreadAndMessage(thread, TestDataFactory.createTestMessage())

        when: "finding by provider thread id"
        def result = threadRepository.findByProviderThreadId("mailgun", "thread-123@mailgun.org")

        then: "thread is returned matching input"
        result.isPresent()
        result.get().id == savedThread.getThreadId()
        result.get().subject == thread.subject
        result.get().provider == thread.provider
        result.get().providerThreadId == thread.providerThreadId
    }

    def "findByProviderThreadId should return empty when thread does not exist"() {
        when: "finding non-existent thread"
        def result = threadRepository.findByProviderThreadId("mailgun", "nonexistent@mailgun.org")

        then: "empty optional is returned"
        result.isEmpty()
    }

    def "findByProviderThreadId should return empty when provider does not match"() {
        given: "a thread exists in database with mailgun provider"
        def thread = TestDataFactory.createTestThread("mailgun", "thread-123@mailgun.org")
        messageRepository.saveThreadAndMessage(thread, TestDataFactory.createTestMessage())

        when: "finding with different provider"
        def result = threadRepository.findByProviderThreadId("sendgrid", "thread-123@mailgun.org")

        then: "empty optional is returned"
        result.isEmpty()
    }

    def "delete should remove thread and cascade to messages"() {
        given: "a thread with message exists in database"
        def thread = TestDataFactory.createTestThread()
        def savedThread = messageRepository.saveThreadAndMessage(thread, TestDataFactory.createTestMessage())

        when: "deleting the thread and trying to find it"
        threadRepository.delete(savedThread.getThreadId())
        threadRepository.findById(savedThread.getThreadId())

        then: "thread is deleted - NotFoundException is thrown"
        thrown(NotFoundException)
    }

    def "softDelete should set deleted_at timestamp"() {
        given: "a thread exists in database"
        def thread = TestDataFactory.createTestThread()
        def savedThread = messageRepository.saveThreadAndMessage(thread, TestDataFactory.createTestMessage())
        def deletedAt = java.time.OffsetDateTime.now()

        when: "soft deleting the thread"
        threadRepository.softDelete(savedThread.getThreadId(), deletedAt)

        then: "thread still exists but has deleted_at set"
        def result = threadRepository.findById(savedThread.getThreadId())
        result.deletedAt != null
    }
}