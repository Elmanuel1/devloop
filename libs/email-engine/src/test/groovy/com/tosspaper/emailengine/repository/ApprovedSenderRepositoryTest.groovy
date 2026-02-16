package com.tosspaper.emailengine.repository

import com.tosspaper.models.domain.ApprovedSender
import com.tosspaper.models.enums.EmailWhitelistValue
import com.tosspaper.models.enums.SenderApprovalStatus
import com.tosspaper.models.exception.NotFoundException
import com.tosspaper.models.service.EmailDomainService
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Unroll

import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.*

class ApprovedSenderRepositoryTest extends BaseRepositoryTest {

    @Autowired
    ApprovedSenderRepository approvedSenderRepository

    @Autowired
    EmailMessageRepository messageRepository

    @Autowired
    EmailThreadRepository threadRepository

    @Autowired
    EmailDomainService emailDomainService

    @Autowired
    DSLContext dsl

    def setup() {
        // Clean all tables before each test
        dsl.deleteFrom(EMAIL_ATTACHMENT).execute()
        dsl.deleteFrom(EMAIL_MESSAGE).execute()
        dsl.deleteFrom(EMAIL_THREAD).execute()
        dsl.deleteFrom(APPROVED_SENDERS).execute()
    }

    def "findByCompanyId should return all approved senders for a company"() {
        given: "multiple approved senders exist for company"
        def sender1 = createApprovedSender("sender1@example.com", SenderApprovalStatus.APPROVED)
        def sender2 = createApprovedSender("sender2@example.com", SenderApprovalStatus.PENDING)
        def sender3 = createApprovedSender("sender3@example.com", SenderApprovalStatus.REJECTED)
        approvedSenderRepository.upsert(sender1)
        approvedSenderRepository.upsert(sender2)
        approvedSenderRepository.upsert(sender3)

        when: "finding all senders for test company"
        def result = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)

        then: "only test company senders are returned"
        result.size() == 3
        result*.senderIdentifier.containsAll(["sender1@example.com", "sender2@example.com", "sender3@example.com"])
        result.every { it.companyId == TestDataFactory.TEST_COMPANY_ID }
    }

    def "findByCompanyId should return empty list when no senders exist"() {
        when: "finding senders for company with no data"
        def result = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)

        then: "empty list is returned"
        result.isEmpty()
    }

    @Unroll
    def "findByCompanyIdAndStatus should return paginated senders filtered by status: #statuses"() {
        given: "multiple approved senders with different statuses"
        approvedSenderRepository.upsert(createApprovedSender("approved1@example.com", SenderApprovalStatus.APPROVED))
        approvedSenderRepository.upsert(createApprovedSender("approved2@example.com", SenderApprovalStatus.APPROVED))
        approvedSenderRepository.upsert(createApprovedSender("pending1@example.com", SenderApprovalStatus.PENDING))
        approvedSenderRepository.upsert(createApprovedSender("pending2@example.com", SenderApprovalStatus.PENDING))
        approvedSenderRepository.upsert(createApprovedSender("rejected1@example.com", SenderApprovalStatus.REJECTED))

        when: "finding by status with pagination"
        def result = approvedSenderRepository.findByCompanyIdAndStatus(
            TestDataFactory.TEST_COMPANY_ID, page, pageSize, statuses as SenderApprovalStatus[]
        )

        then: "correct senders are returned with pagination info"
        result.data.size() == expectedSize
        result.data.every { statuses.contains(it.status) }
        result.pagination.page == page
        result.pagination.pageSize == pageSize
        result.pagination.totalItems == expectedTotalItems
        result.pagination.totalPages == expectedTotalPages

        where:
        statuses                                          | page | pageSize || expectedSize | expectedTotalItems | expectedTotalPages
        [SenderApprovalStatus.APPROVED]                   | 1    | 10       || 2            | 2                  | 1
        [SenderApprovalStatus.PENDING]                    | 1    | 10       || 2            | 2                  | 1
        [SenderApprovalStatus.REJECTED]                   | 1    | 10       || 1            | 1                  | 1
        [SenderApprovalStatus.APPROVED, SenderApprovalStatus.PENDING] | 1 | 10 || 4       | 4                  | 1
        [SenderApprovalStatus.APPROVED]                   | 1    | 1        || 1            | 2                  | 2
        [SenderApprovalStatus.APPROVED]                   | 2    | 1        || 1            | 2                  | 2
    }

    def "findByCompanyIdAndStatus should return all statuses when no status filter provided"() {
        given: "senders with mixed statuses"
        approvedSenderRepository.upsert(createApprovedSender("approved@example.com", SenderApprovalStatus.APPROVED))
        approvedSenderRepository.upsert(createApprovedSender("pending@example.com", SenderApprovalStatus.PENDING))
        approvedSenderRepository.upsert(createApprovedSender("rejected@example.com", SenderApprovalStatus.REJECTED))

        when: "finding without status filter"
        def result = approvedSenderRepository.findByCompanyIdAndStatus(
            TestDataFactory.TEST_COMPANY_ID, 1, 10
        )

        then: "all senders are returned"
        result.data.size() == 3
    }

    def "upsert should insert new approved sender"() {
        given: "a new approved sender"
        def sender = createApprovedSender("newSender@example.com", SenderApprovalStatus.APPROVED)

        when: "upserting the sender"
        def result = approvedSenderRepository.upsert(sender)

        then: "sender is inserted with generated ID and fields matching input"
        result.id != null
        result.companyId == sender.companyId
        result.senderIdentifier == sender.senderIdentifier
        result.whitelistType == sender.whitelistType
        result.status == sender.status
        result.approvedBy == sender.approvedBy
        result.createdAt != null
        // updatedAt is null on insert, only set on update
    }

    def "upsert should update existing approved sender"() {
        given: "an existing approved sender"
        def original = createApprovedSender("existing@example.com", SenderApprovalStatus.PENDING)
        def inserted = approvedSenderRepository.upsert(original)

        and: "an update with the same identifier but different status"
        def update = ApprovedSender.builder()
            .companyId(TestDataFactory.TEST_COMPANY_ID)
            .senderIdentifier("existing@example.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.APPROVED)
            .approvedBy("user-456")
            .scheduledDeletionAt(null)
            .build()

        when: "upserting the update"
        def result = approvedSenderRepository.upsert(update)

        then: "existing record is updated"
        result.id == inserted.id
        result.status == SenderApprovalStatus.APPROVED
        result.approvedBy == "user-456"
        result.updatedAt > inserted.updatedAt
    }

    def "insertIfNotExists should insert new sender and return true"() {
        given: "a new approved sender"
        def sender = createApprovedSender("new@example.com", SenderApprovalStatus.PENDING)

        when: "inserting if not exists"
        def wasInserted = approvedSenderRepository.insertIfNotExists(sender)

        then: "sender is inserted"
        wasInserted

        and: "sender can be retrieved"
        def found = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)
        found.size() == 1
        found[0].senderIdentifier == "new@example.com"
    }

    def "insertIfNotExists should not insert duplicate sender and return false"() {
        given: "an existing approved sender"
        def existing = createApprovedSender("existing@example.com", SenderApprovalStatus.PENDING)
        approvedSenderRepository.upsert(existing)

        and: "a duplicate sender"
        def duplicate = createApprovedSender("existing@example.com", SenderApprovalStatus.APPROVED)

        when: "inserting duplicate"
        def wasInserted = approvedSenderRepository.insertIfNotExists(duplicate)

        then: "no insert occurs"
        !wasInserted

        and: "original sender is unchanged"
        def found = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)
        found.size() == 1
        found[0].status == SenderApprovalStatus.PENDING
    }

    def "findPendingDocumentsGroupedBySender should return grouped documents by sender"() {
        given: "messages with attachments from pending senders"
        def assignedEmail = "recipient@tosspaper.com"
        def sender1 = "pending1@example.com"
        def sender2 = "pending2@example.com"

        // Create pending sender approvals
        approvedSenderRepository.upsert(createApprovedSender(sender1, SenderApprovalStatus.PENDING))
        approvedSenderRepository.upsert(createApprovedSender(sender2, SenderApprovalStatus.PENDING))

        // Create messages and attachments
        def thread1 = TestDataFactory.createTestThread()
        def message1 = TestDataFactory.createTestMessage()
        message1.fromAddress = sender1
        message1.toAddress = assignedEmail
        def savedMessage1 = messageRepository.saveThreadAndMessage(thread1, message1)

        insertAttachment(savedMessage1.id, "doc1.pdf")
        insertAttachment(savedMessage1.id, "doc2.pdf")

        def thread2 = TestDataFactory.createTestThread("mailgun", "thread2@mailgun.org")
        def message2 = TestDataFactory.createTestMessage("mailgun", "msg2@mailgun.org")
        message2.fromAddress = sender2
        message2.toAddress = assignedEmail
        def savedMessage2 = messageRepository.saveThreadAndMessage(thread2, message2)

        insertAttachment(savedMessage2.id, "doc3.pdf")

        when: "finding pending documents grouped by sender"
        def result = approvedSenderRepository.findPendingDocumentsGroupedBySender(
            TestDataFactory.TEST_COMPANY_ID, assignedEmail
        )

        then: "documents are grouped by sender"
        result.size() == 2

        def group1 = result.find { it.senderIdentifier == sender1 }
        group1 != null
        group1.documentsPending == 2
        group1.attachments.size() == 2

        def group2 = result.find { it.senderIdentifier == sender2 }
        group2 != null
        group2.documentsPending == 1
        group2.attachments.size() == 1
    }

    def "findPendingDocumentsGroupedBySender should exclude soft-deleted threads"() {
        given: "a pending sender with a soft-deleted thread"
        def sender = "pending@example.com"
        def assignedEmail = "recipient@tosspaper.com"

        approvedSenderRepository.upsert(createApprovedSender(sender, SenderApprovalStatus.PENDING))

        def thread = TestDataFactory.createTestThread()
        def message = TestDataFactory.createTestMessage()
        message.fromAddress = sender
        message.toAddress = assignedEmail
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)

        insertAttachment(savedMessage.id, "doc.pdf")

        // Soft delete the thread
        dsl.update(EMAIL_THREAD)
            .set(EMAIL_THREAD.DELETED_AT, OffsetDateTime.now())
            .where(EMAIL_THREAD.ID.eq(savedMessage.threadId))
            .execute()

        when: "finding pending documents"
        def result = approvedSenderRepository.findPendingDocumentsGroupedBySender(
            TestDataFactory.TEST_COMPANY_ID, assignedEmail
        )

        then: "soft-deleted thread is excluded"
        result.isEmpty()
    }

    def "delete should remove approved sender"() {
        given: "an existing approved sender"
        def sender = createApprovedSender("todelete@example.com", SenderApprovalStatus.APPROVED)
        def saved = approvedSenderRepository.upsert(sender)

        when: "deleting the sender"
        approvedSenderRepository.delete(saved.id, TestDataFactory.TEST_COMPANY_ID)

        then: "sender is deleted"
        def remaining = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)
        remaining.isEmpty()
    }

    def "delete should throw NotFoundException when sender does not exist"() {
        when: "deleting non-existent sender"
        approvedSenderRepository.delete("non-existent-id", TestDataFactory.TEST_COMPANY_ID)

        then: "NotFoundException is thrown"
        thrown(NotFoundException)
    }

    def "delete should throw NotFoundException when company ID does not match"() {
        given: "a sender for test company"
        def sender = createApprovedSender("other@example.com", SenderApprovalStatus.APPROVED)
        def saved = approvedSenderRepository.upsert(sender)

        when: "deleting with wrong company ID"
        approvedSenderRepository.delete(saved.id, 999L)

        then: "NotFoundException is thrown"
        thrown(NotFoundException)
    }

    def "approveDomainAndRestoreThreads should approve domain and restore threads"() {
        given: "pending email senders from the same domain with soft-deleted threads"
        def domain = "example.com"
        def email1 = "user1@example.com"
        def email2 = "user2@example.com"

        // Create pending senders
        approvedSenderRepository.upsert(createApprovedSender(email1, SenderApprovalStatus.PENDING))
        approvedSenderRepository.upsert(createApprovedSender(email2, SenderApprovalStatus.PENDING))

        // Create threads and soft-delete them
        def thread1 = createThreadForSender(email1)
        def thread2 = createThreadForSender(email2)

        dsl.update(EMAIL_THREAD)
            .set(EMAIL_THREAD.DELETED_AT, OffsetDateTime.now())
            .where(EMAIL_THREAD.ID.in(thread1.threadId, thread2.threadId))
            .execute()

        when: "approving the domain"
        def updatedCount = approvedSenderRepository.approveDomainAndRestoreThreads(
            TestDataFactory.TEST_COMPANY_ID, domain, "admin-user"
        )

        then: "email-level senders are updated to approved"
        updatedCount == 2

        and: "threads are restored"
        def restoredThread1 = threadRepository.findById(thread1.threadId)
        restoredThread1 != null

        def restoredThread2 = threadRepository.findById(thread2.threadId)
        restoredThread2 != null

        and: "domain-level approval record is created"
        def domainSenders = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)
        def domainApproval = domainSenders.find { it.senderIdentifier == domain }
        domainApproval != null
        domainApproval.whitelistType == EmailWhitelistValue.DOMAIN
        domainApproval.status == SenderApprovalStatus.APPROVED
        domainApproval.approvedBy == "admin-user"
    }

    def "approveEmailAndRestoreThreads should approve email and restore threads"() {
        given: "a pending sender with soft-deleted thread"
        def email = "sender@example.com"
        approvedSenderRepository.upsert(createApprovedSender(email, SenderApprovalStatus.PENDING))

        def thread = createThreadForSender(email)

        // Soft delete the thread
        dsl.update(EMAIL_THREAD)
            .set(EMAIL_THREAD.DELETED_AT, OffsetDateTime.now())
            .where(EMAIL_THREAD.ID.eq(thread.threadId))
            .execute()

        and: "an approval request"
        def approvalRequest = ApprovedSender.builder()
            .companyId(TestDataFactory.TEST_COMPANY_ID)
            .senderIdentifier(email)
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.APPROVED)
            .approvedBy("user-123")
            .build()

        when: "approving the email"
        def threadsRestored = approvedSenderRepository.approveEmailAndRestoreThreads(approvalRequest)

        then: "thread is restored"
        threadsRestored == 1

        and: "sender is approved"
        def senders = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)
        def approved = senders.find { it.senderIdentifier == email }
        approved.status == SenderApprovalStatus.APPROVED
        approved.approvedBy == "user-123"

        and: "thread is accessible"
        def restoredThread = threadRepository.findById(thread.threadId)
        restoredThread != null
    }

    def "rejectEmailAndSoftDeleteThreads should reject email and soft-delete threads without extractions"() {
        given: "a pending sender with thread that has no extraction records"
        def email = "reject@example.com"
        approvedSenderRepository.upsert(createApprovedSender(email, SenderApprovalStatus.PENDING))

        def thread = createThreadForSender(email)

        and: "scheduled deletion time"
        def scheduledDeletion = OffsetDateTime.now().plusDays(30)

        when: "rejecting the email"
        def threadsDeleted = approvedSenderRepository.rejectEmailAndSoftDeleteThreads(
            TestDataFactory.TEST_COMPANY_ID, email, "user-123", scheduledDeletion
        )

        then: "thread is soft-deleted"
        threadsDeleted == 1

        and: "sender is rejected"
        def senders = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)
        def rejected = senders.find { it.senderIdentifier == email }
        rejected.status == SenderApprovalStatus.REJECTED
        rejected.approvedBy == "user-123"
        rejected.scheduledDeletionAt.toInstant() == scheduledDeletion.toInstant()

        and: "thread is soft-deleted"
        def threadRecord = dsl.selectFrom(EMAIL_THREAD)
            .where(EMAIL_THREAD.ID.eq(thread.threadId))
            .fetchOne()
        threadRecord.getDeletedAt() != null
    }

    def "rejectEmailAndSoftDeleteThreads should not delete threads with extraction records"() {
        given: "a pending sender with thread that has extraction record"
        def email = "reject@example.com"
        approvedSenderRepository.upsert(createApprovedSender(email, SenderApprovalStatus.PENDING))

        def thread = createThreadForSender(email)

        // Create attachment and extraction task
        def attachment = insertAttachment(thread.messageId, "doc.pdf")
        insertExtractionTask(attachment.assignedId, thread.messageId, thread.threadId)

        when: "rejecting the email"
        def threadsDeleted = approvedSenderRepository.rejectEmailAndSoftDeleteThreads(
            TestDataFactory.TEST_COMPANY_ID, email, "user-123", OffsetDateTime.now().plusDays(30)
        )

        then: "no threads are deleted (has extraction)"
        threadsDeleted == 0

        and: "thread remains active"
        def threadRecord = dsl.selectFrom(EMAIL_THREAD)
            .where(EMAIL_THREAD.ID.eq(thread.threadId))
            .fetchOne()
        threadRecord.getDeletedAt() == null
    }

    def "rejectDomainAndSoftDeleteThreads should reject domain and soft-delete threads"() {
        given: "pending senders from the same domain"
        def domain = "reject-domain.com"
        def email1 = "user1@reject-domain.com"
        def email2 = "user2@reject-domain.com"

        approvedSenderRepository.upsert(createApprovedSender(email1, SenderApprovalStatus.PENDING))
        approvedSenderRepository.upsert(createApprovedSender(email2, SenderApprovalStatus.PENDING))

        def thread1 = createThreadForSender(email1)
        def thread2 = createThreadForSender(email2)

        and: "scheduled deletion time"
        def scheduledDeletion = OffsetDateTime.now().plusDays(30)

        when: "rejecting the domain"
        def threadsDeleted = approvedSenderRepository.rejectDomainAndSoftDeleteThreads(
            TestDataFactory.TEST_COMPANY_ID, domain, "admin-user", scheduledDeletion
        )

        then: "threads are soft-deleted"
        threadsDeleted == 2

        and: "email-level senders are rejected"
        def senders = approvedSenderRepository.findByCompanyId(TestDataFactory.TEST_COMPANY_ID)
        senders.findAll { it.whitelistType == EmailWhitelistValue.EMAIL }.every {
            it.status == SenderApprovalStatus.REJECTED
            it.scheduledDeletionAt.toInstant() == scheduledDeletion.toInstant()
        }

        and: "domain-level rejection record is created"
        def domainRejection = senders.find { it.senderIdentifier == domain }
        domainRejection != null
        domainRejection.whitelistType == EmailWhitelistValue.DOMAIN
        domainRejection.status == SenderApprovalStatus.REJECTED
        domainRejection.approvedBy == "admin-user"
    }

    // Helper methods

    private ApprovedSender createApprovedSender(
        String senderIdentifier,
        SenderApprovalStatus status,
        Long companyId = TestDataFactory.TEST_COMPANY_ID
    ) {
        return ApprovedSender.builder()
            .companyId(companyId)
            .senderIdentifier(senderIdentifier)
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(status)
            .approvedBy("user-123")
            .scheduledDeletionAt(null)
            .build()
    }

    private Map<String, Object> createThreadForSender(String senderEmail) {
        def uniqueId = UUID.randomUUID().toString().take(8)
        def thread = TestDataFactory.createTestThread("mailgun", "thread-${uniqueId}@mailgun.org")
        def message = TestDataFactory.createTestMessage("mailgun", "msg-${uniqueId}@mailgun.org")
        message.fromAddress = senderEmail
        def savedMessage = messageRepository.saveThreadAndMessage(thread, message)
        return [threadId: savedMessage.threadId, messageId: savedMessage.id]
    }

    private Map<String, String> insertAttachment(UUID messageId, String fileName) {
        def assignedId = "attach_${UUID.randomUUID().toString().take(8)}"
        dsl.insertInto(EMAIL_ATTACHMENT)
            .set(EMAIL_ATTACHMENT.MESSAGE_ID, messageId)
            .set(EMAIL_ATTACHMENT.ASSIGNED_ID, assignedId)
            .set(EMAIL_ATTACHMENT.FILE_NAME, fileName)
            .set(EMAIL_ATTACHMENT.CONTENT_TYPE, "application/pdf")
            .set(EMAIL_ATTACHMENT.SIZE_BYTES, 1024L)
            .set(EMAIL_ATTACHMENT.STORAGE_URL, "s3://bucket/${fileName}")
            .set(EMAIL_ATTACHMENT.LOCAL_FILE_PATH, "/tmp/${fileName}")
            .set(EMAIL_ATTACHMENT.CHECKSUM, "abc123")
            .set(EMAIL_ATTACHMENT.STATUS, "pending")
            .execute()
        return [assignedId: assignedId, fileName: fileName]
    }

    private void insertExtractionTask(String assignedId, UUID messageId, UUID threadId) {
        dsl.insertInto(EXTRACTION_TASK)
            .set(EXTRACTION_TASK.ASSIGNED_ID, assignedId)
            .set(EXTRACTION_TASK.COMPANY_ID, TestDataFactory.TEST_COMPANY_ID)
            .set(EXTRACTION_TASK.STORAGE_KEY, "s3://bucket/${assignedId}.pdf")
            .set(EXTRACTION_TASK.EMAIL_MESSAGE_ID, messageId)
            .set(EXTRACTION_TASK.EMAIL_THREAD_ID, threadId)
            .set(EXTRACTION_TASK.STATUS, "completed")
            .execute()
    }
}
