package com.tosspaper.emailengine.service

import com.tosspaper.emailengine.repository.EmailAttachmentRepository
import com.tosspaper.emailengine.repository.EmailMessageRepository
import com.tosspaper.emailengine.repository.EmailThreadRepository
import com.tosspaper.emailengine.service.impl.EmailServiceImpl
import com.tosspaper.models.domain.EmailAttachment
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.domain.EmailThread
import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.enums.MessageDirection
import com.tosspaper.models.enums.MessageStatus
import com.tosspaper.models.exception.DuplicateException
import com.tosspaper.models.service.CompanyLookupService
import com.tosspaper.models.service.RedisStreamPublisher
import com.tosspaper.models.service.SenderNotificationService
import com.tosspaper.models.service.StorageService
import com.tosspaper.models.storage.UploadResult
import com.tosspaper.models.validation.FileValidationChain
import com.tosspaper.models.validation.ValidationResult
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive unit tests for EmailServiceImpl.
 * Tests complete email processing workflow including:
 * - Thread management (new threads, existing threads, replies)
 * - Message persistence and status transitions
 * - Attachment processing and validation
 * - Company lookup
 * - Stream publishing
 * - Error handling and edge cases
 */
class EmailProcessorTest extends Specification {

    EmailMessageRepository emailMessageRepository = Mock()
    EmailThreadRepository emailThreadRepository = Mock()
    EmailAttachmentRepository emailAttachmentRepository = Mock()
    CompanyLookupService companyLookupService = Mock()
    RedisStreamPublisher streamPublisher = Mock()
    StorageService filesystemStorageService = Mock()
    SenderNotificationService senderNotificationService = Mock()
    FileValidationChain fileValidationChain = Mock()

    @Subject
    EmailServiceImpl emailService

    def setup() {
        emailService = new EmailServiceImpl(
            emailMessageRepository,
            emailThreadRepository,
            emailAttachmentRepository,
            companyLookupService,
            streamPublisher,
            filesystemStorageService,
            senderNotificationService,
            fileValidationChain
        )
    }

    // ==================== NEW MESSAGE (NEW THREAD) TESTS ====================

    def "should process new email and create new thread when no inReplyTo"() {
        given: "a new email message without inReplyTo"
        def emailMessage = createTestEmailMessage()
        def savedMessage = createTestEmailMessage()
        savedMessage.id = UUID.randomUUID()
        savedMessage.threadId = UUID.randomUUID()

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should lookup company by toAddress"
        1 * companyLookupService.getCompanyByAssignedEmail("recipient@example.com") >> Optional.empty()

        and: "should save new thread and message atomically"
        1 * emailMessageRepository.saveThreadAndMessage({ EmailThread thread ->
            thread.subject == "Test Email" &&
            thread.provider == "mailgun" &&
            thread.providerThreadId == "msg-123@mailgun.org"
        }, emailMessage) >> savedMessage

        and: "should claim message for processing (RECEIVED -> PROCESSING)"
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true

        and: "should send no-attachment notification"
        1 * senderNotificationService.sendNoAttachmentNotification(emailMessage)

        and: "should update status to PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should set company ID when company is found"() {
        given: "an email to a known company address"
        def emailMessage = createTestEmailMessage()
        def savedMessage = createTestEmailMessage()
        savedMessage.id = UUID.randomUUID()
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(123L, "invoices@company.example.com", "owner@example.com", "Test Company")

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should lookup and set company ID"
        1 * companyLookupService.getCompanyByAssignedEmail("recipient@example.com") >> Optional.of(companyInfo)

        and: "company ID should be set on message"
        emailMessage.companyId == 123L

        and: "should continue with normal processing"
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true
        1 * senderNotificationService.sendNoAttachmentNotification(_)
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    // ==================== REPLY MESSAGE (EXISTING THREAD) TESTS ====================

    def "should add message to existing thread when inReplyTo matches"() {
        given: "an email replying to existing thread"
        def emailMessage = createTestEmailMessage()
        emailMessage.inReplyTo = "original-msg@mailgun.org"

        def existingThread = createTestThread()
        existingThread.id = UUID.randomUUID()

        def savedMessage = createTestEmailMessage()
        savedMessage.id = UUID.randomUUID()
        savedMessage.threadId = existingThread.id

        when: "processing the reply"
        emailService.processWebhook(emailMessage)

        then: "should find existing thread by inReplyTo"
        1 * emailThreadRepository.findByProviderThreadId("mailgun", "original-msg@mailgun.org") >> Optional.of(existingThread)

        and: "should save message only (not create new thread)"
        1 * emailMessageRepository.save({ EmailMessage msg ->
            msg.threadId == existingThread.id
        }) >> savedMessage

        and: "should NOT call saveThreadAndMessage"
        0 * emailMessageRepository.saveThreadAndMessage(_, _)

        and: "should process normally"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true
        1 * senderNotificationService.sendNoAttachmentNotification(_)
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should create new thread when inReplyTo thread not found"() {
        given: "an email with inReplyTo that doesn't match any thread"
        def emailMessage = createTestEmailMessage()
        emailMessage.inReplyTo = "nonexistent-msg@mailgun.org"

        def savedMessage = createTestEmailMessage()
        savedMessage.id = UUID.randomUUID()

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should try to find thread by inReplyTo"
        1 * emailThreadRepository.findByProviderThreadId("mailgun", "nonexistent-msg@mailgun.org") >> Optional.empty()

        and: "should create new thread since original not found"
        1 * emailMessageRepository.saveThreadAndMessage({ EmailThread thread ->
            thread.providerThreadId == "nonexistent-msg@mailgun.org"
        }, emailMessage) >> savedMessage

        and: "should process normally"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true
        1 * senderNotificationService.sendNoAttachmentNotification(_)
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    // ==================== DUPLICATE / CONCURRENT HANDLING TESTS ====================

    def "should skip processing when message already claimed by another process"() {
        given: "an email that's being processed concurrently"
        def emailMessage = createTestEmailMessage()
        def savedMessage = createTestEmailMessage()
        savedMessage.id = UUID.randomUUID()

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should save message"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage

        and: "should fail to claim (already claimed by another process)"
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> false

        and: "should NOT process attachments or update status"
        0 * senderNotificationService._
        0 * filesystemStorageService._
        0 * emailAttachmentRepository._
        0 * streamPublisher._
        0 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should propagate DuplicateException when message already exists"() {
        given: "an email that already exists in database"
        def emailMessage = createTestEmailMessage()

        when: "processing the duplicate email"
        emailService.processWebhook(emailMessage)

        then: "should try to save and get duplicate error"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> { throw new DuplicateException("Duplicate message") }

        and: "should propagate the exception"
        def exception = thrown(DuplicateException)
        exception.message == "Duplicate message"
    }

    // ==================== ATTACHMENT PROCESSING TESTS ====================

    def "should process valid PDF attachment successfully"() {
        given: "an email with valid PDF attachment"
        def attachment = createTestFileObject("invoice.pdf", "application/pdf")
        def emailMessage = createTestEmailMessage([attachment])
        def savedMessage = createTestEmailMessage([attachment])
        savedMessage.id = UUID.randomUUID()

        def uploadResult = UploadResult.success("/tmp/uploads/invoice.pdf", "sha256abc", 1024L, "application/pdf")

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should save message"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true

        and: "should validate attachment"
        1 * fileValidationChain.validate(attachment) >> ValidationResult.valid()

        and: "should upload to filesystem storage"
        1 * filesystemStorageService.uploadFile(attachment) >> uploadResult

        and: "should save attachment record"
        1 * emailAttachmentRepository.saveAll({ List<EmailAttachment> attachments ->
            attachments.size() == 1 &&
            attachments[0].fileName == "invoice.pdf" &&
            attachments[0].contentType == "application/pdf"
        })

        and: "should publish to stream"
        1 * streamPublisher.publish("email-local-uploads", { Map msg ->
            msg.assignedId == attachment.assignedId
        })

        and: "should NOT send no-attachment notification"
        0 * senderNotificationService.sendNoAttachmentNotification(_)

        and: "should update status to PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should process valid image attachments (JPEG, PNG, WebP)"() {
        given: "an email with image attachments"
        def jpegAttachment = createTestFileObject("photo.jpg", "image/jpeg")
        def pngAttachment = createTestFileObject("screenshot.png", "image/png")
        def webpAttachment = createTestFileObject("image.webp", "image/webp")
        def emailMessage = createTestEmailMessage([jpegAttachment, pngAttachment, webpAttachment])
        def savedMessage = createTestEmailMessage([jpegAttachment, pngAttachment, webpAttachment])
        savedMessage.id = UUID.randomUUID()

        def uploadResult = UploadResult.success("/tmp/uploads/file", "sha256", 1024L, "image/jpeg")

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should save message"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true

        and: "should validate all attachments as valid"
        1 * fileValidationChain.validate(jpegAttachment) >> ValidationResult.valid()
        1 * fileValidationChain.validate(pngAttachment) >> ValidationResult.valid()
        1 * fileValidationChain.validate(webpAttachment) >> ValidationResult.valid()

        and: "should upload all attachments"
        3 * filesystemStorageService.uploadFile(_) >> uploadResult

        and: "should save all attachment records"
        3 * emailAttachmentRepository.saveAll(_)

        and: "should publish all to stream"
        3 * streamPublisher.publish("email-local-uploads", _)

        and: "should update status to PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should reject invalid file types and notify sender"() {
        given: "an email with unsupported file type"
        def exeAttachment = createTestFileObject("malware.exe", "application/x-executable")
        def emailMessage = createTestEmailMessage([exeAttachment])
        def savedMessage = createTestEmailMessage([exeAttachment])
        savedMessage.id = UUID.randomUUID()

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should save message"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true

        and: "should validate and reject attachment"
        1 * fileValidationChain.validate(exeAttachment) >> ValidationResult.invalid(["Unsupported file type"])

        and: "should send unsupported file type notification"
        1 * senderNotificationService.sendUnsupportedFileTypeNotification(emailMessage, { List files ->
            files.size() == 1 && files[0].fileName == "malware.exe"
        })

        and: "should NOT upload invalid files"
        0 * filesystemStorageService.uploadFile(exeAttachment)

        and: "should update status to PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should process mixed valid and invalid attachments"() {
        given: "an email with both valid PDF and invalid script"
        def validPdf = createTestFileObject("document.pdf", "application/pdf")
        def invalidScript = createTestFileObject("hack.sh", "application/x-sh")
        def emailMessage = createTestEmailMessage([validPdf, invalidScript])
        def savedMessage = createTestEmailMessage([validPdf, invalidScript])
        savedMessage.id = UUID.randomUUID()

        def uploadResult = UploadResult.success("/tmp/uploads/document.pdf", "sha256", 1024L, "application/pdf")

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should save message"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true

        and: "should validate both attachments"
        1 * fileValidationChain.validate(validPdf) >> ValidationResult.valid()
        1 * fileValidationChain.validate(invalidScript) >> ValidationResult.invalid(["Unsupported script file"])

        and: "should notify about invalid file only"
        1 * senderNotificationService.sendUnsupportedFileTypeNotification(emailMessage, { List files ->
            files.size() == 1 && files[0].fileName == "hack.sh"
        })

        and: "should upload only valid attachment"
        1 * filesystemStorageService.uploadFile(validPdf) >> uploadResult
        0 * filesystemStorageService.uploadFile(invalidScript)

        and: "should save only valid attachment record"
        1 * emailAttachmentRepository.saveAll({ List<EmailAttachment> attachments ->
            attachments.size() == 1 && attachments[0].fileName == "document.pdf"
        })

        and: "should publish only valid attachment"
        1 * streamPublisher.publish("email-local-uploads", _)

        and: "should update status to PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should handle attachment upload failure gracefully"() {
        given: "an email with attachment that fails to upload"
        def attachment = createTestFileObject("document.pdf", "application/pdf")
        def emailMessage = createTestEmailMessage([attachment])
        def savedMessage = createTestEmailMessage([attachment])
        savedMessage.id = UUID.randomUUID()

        def failedUpload = UploadResult.failure("Disk full")

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should save message"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true

        and: "should validate attachment"
        1 * fileValidationChain.validate(attachment) >> ValidationResult.valid()

        and: "should try to upload but fail"
        1 * filesystemStorageService.uploadFile(attachment) >> failedUpload

        and: "should save attachment record with failed status"
        1 * emailAttachmentRepository.saveAll(_)

        and: "should NOT publish to stream on upload failure"
        0 * streamPublisher.publish("email-local-uploads", _)

        and: "should still update status to PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    // ==================== EDGE CASES ====================

    def "should handle null attachments list"() {
        given: "an email with null attachments"
        def emailMessage = createTestEmailMessage()
        emailMessage.attachments = null
        def savedMessage = createTestEmailMessage()
        savedMessage.id = UUID.randomUUID()

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should process without errors"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true
        1 * senderNotificationService.sendNoAttachmentNotification(_)
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)

        and: "should NOT try to process attachments"
        0 * fileValidationChain._
        0 * filesystemStorageService._
        0 * emailAttachmentRepository._
    }

    def "should handle empty attachments list"() {
        given: "an email with empty attachments list"
        def emailMessage = createTestEmailMessage([])
        def savedMessage = createTestEmailMessage([])
        savedMessage.id = UUID.randomUUID()

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should process without errors"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true
        1 * senderNotificationService.sendNoAttachmentNotification(_)
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)

        and: "should NOT try to process attachments"
        0 * fileValidationChain._
        0 * filesystemStorageService._
        0 * emailAttachmentRepository._
    }

    def "should handle repository exception during message save"() {
        given: "an email message"
        def emailMessage = createTestEmailMessage()

        when: "processing fails due to repository error"
        emailService.processWebhook(emailMessage)

        then: "should propagate the exception"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> { throw new RuntimeException("Database connection lost") }

        and: "should throw RuntimeException"
        def exception = thrown(RuntimeException)
        exception.message == "Database connection lost"
    }

    def "should handle attachment record save exception gracefully"() {
        given: "an email with attachment that fails to save record"
        def attachment = createTestFileObject("document.pdf", "application/pdf")
        def emailMessage = createTestEmailMessage([attachment])
        def savedMessage = createTestEmailMessage([attachment])
        savedMessage.id = UUID.randomUUID()

        def uploadResult = UploadResult.success("/tmp/uploads/document.pdf", "sha256", 1024L, "application/pdf")

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should save message"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true
        1 * fileValidationChain.validate(attachment) >> ValidationResult.valid()
        1 * filesystemStorageService.uploadFile(attachment) >> uploadResult

        and: "attachment save fails"
        1 * emailAttachmentRepository.saveAll(_) >> { throw new RuntimeException("Attachment DB error") }

        and: "should NOT publish (exception stops processing for this attachment)"
        0 * streamPublisher.publish(_, _)

        and: "should still update status to PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    def "should handle company lookup failure gracefully"() {
        given: "an email to unknown company"
        def emailMessage = createTestEmailMessage()
        def savedMessage = createTestEmailMessage()
        savedMessage.id = UUID.randomUUID()

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "should continue processing without company ID"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()

        and: "company ID should remain null"
        emailMessage.companyId == null

        and: "should process normally"
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true
        1 * senderNotificationService.sendNoAttachmentNotification(_)
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    // ==================== STATUS TRANSITION TESTS ====================

    def "should transition through all status states correctly"() {
        given: "an email with attachment"
        def attachment = createTestFileObject("doc.pdf", "application/pdf")
        def emailMessage = createTestEmailMessage([attachment])
        def savedMessage = createTestEmailMessage([attachment])
        savedMessage.id = UUID.randomUUID()
        def uploadResult = UploadResult.success("/tmp/doc.pdf", "sha256", 100L, "application/pdf")

        when: "processing the email"
        emailService.processWebhook(emailMessage)

        then: "status transitions: RECEIVED -> PROCESSING -> PROCESSED"
        1 * companyLookupService.getCompanyByAssignedEmail(_) >> Optional.empty()
        1 * emailMessageRepository.saveThreadAndMessage(_, _) >> savedMessage

        then: "first transition: RECEIVED -> PROCESSING"
        1 * emailMessageRepository.updateStatus(savedMessage.id, MessageStatus.RECEIVED, MessageStatus.PROCESSING) >> true

        then: "process attachments"
        1 * fileValidationChain.validate(_) >> ValidationResult.valid()
        1 * filesystemStorageService.uploadFile(_) >> uploadResult
        1 * emailAttachmentRepository.saveAll(_)
        1 * streamPublisher.publish(_, _)

        then: "final transition: PROCESSING -> PROCESSED"
        1 * emailMessageRepository.updateStatus(savedMessage.id, null, MessageStatus.PROCESSED)
    }

    // ==================== HELPER METHODS ====================

    private static EmailMessage createTestEmailMessage(List<FileObject> attachments = null) {
        return EmailMessage.builder()
            .provider("mailgun")
            .providerMessageId("msg-123@mailgun.org")
            .fromAddress("sender@example.com")
            .toAddress("recipient@example.com")
            .subject("Test Email")
            .bodyText("This is a test email from Mailgun")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .providerTimestamp(OffsetDateTime.now())
            .attachments(attachments)
            .attachmentsCount(attachments?.size() ?: 0)
            .build()
    }

    private static EmailThread createTestThread() {
        return EmailThread.builder()
            .subject("Test Email")
            .provider("mailgun")
            .providerThreadId("msg-123@mailgun.org")
            .messageCount(1)
            .createdAt(OffsetDateTime.now())
            .build()
    }

    private static FileObject createTestFileObject(String fileName, String contentType) {
        return FileObject.builder()
            .assignedId("assigned-" + UUID.randomUUID().toString())
            .fileName(fileName)
            .contentType(contentType)
            .content("test content".bytes)
            .sizeBytes(12L)
            .checksum("sha256abc123")
            .build()
    }
}