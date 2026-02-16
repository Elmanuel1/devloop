package com.tosspaper.emailengine.streams

import com.tosspaper.emailengine.repository.ApprovedSenderRepository
import com.tosspaper.emailengine.repository.EmailAttachmentRepository
import com.tosspaper.emailengine.repository.EmailMessageRepository
import com.tosspaper.emailengine.service.SenderValidationService
import com.tosspaper.emailengine.service.dto.ValidationAction
import com.tosspaper.emailengine.service.dto.ValidationResult
import com.tosspaper.models.domain.ApprovedSender
import com.tosspaper.models.domain.AttachmentStatus
import com.tosspaper.models.domain.EmailAttachment
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.enums.EmailWhitelistValue
import com.tosspaper.models.enums.SenderApprovalStatus
import com.tosspaper.models.messaging.MessagePublisher
import com.tosspaper.models.service.SenderApprovalNotificationService
import com.tosspaper.models.service.SenderNotificationService
import com.tosspaper.models.service.StorageService
import com.tosspaper.models.storage.S3UploadResult
import spock.lang.Specification
import spock.lang.Subject

import java.nio.file.Files
import java.nio.file.Paths
import java.time.OffsetDateTime

/**
 * Tests for EmailLocalUploadsHandler to ensure attachment upload
 * processing, sender validation, and queue publishing work correctly.
 */
class EmailLocalUploadsHandlerSpec extends Specification {

    EmailAttachmentRepository emailAttachmentRepository = Mock()
    StorageService s3StorageService = Mock()
    MessagePublisher messagePublisher = Mock()
    EmailMessageRepository emailMessageRepository = Mock()
    ApprovedSenderRepository approvedSenderRepository = Mock()
    SenderValidationService senderValidationService = Mock()
    SenderApprovalNotificationService senderApprovalNotificationService = Mock()
    SenderNotificationService senderNotificationService = Mock()

    @Subject
    EmailLocalUploadsHandler handler

    def setup() {
        handler = new EmailLocalUploadsHandler(
            emailAttachmentRepository,
            s3StorageService,
            messagePublisher,
            emailMessageRepository,
            approvedSenderRepository,
            senderValidationService,
            senderApprovalNotificationService,
            senderNotificationService
        )
    }

    // ==================== Queue Name Tests ====================

    def "should return correct queue name"() {
        expect:
        handler.getQueueName() == "email-local-uploads"
    }

    // ==================== Message Processing Tests ====================

    def "should warn and return when assignedId is missing"() {
        given:
        def message = [:]

        when:
        handler.handle(message)

        then:
        0 * emailAttachmentRepository._
    }

    def "should warn when attachment not found"() {
        given:
        def message = ["assignedId": "att-123"]
        emailAttachmentRepository.findByAssignedId("att-123") >> Optional.empty()

        when:
        handler.handle(message)

        then:
        0 * emailMessageRepository._
    }

    def "should skip attachment that is not in pending or processing status"() {
        given:
        def message = ["assignedId": "att-456"]
        def attachment = EmailAttachment.builder()
            .assignedId("att-456")
            .status(AttachmentStatus.uploaded)
            .build()

        emailAttachmentRepository.findByAssignedId("att-456") >> Optional.of(attachment)

        when:
        handler.handle(message)

        then:
        0 * emailAttachmentRepository.updateStatusToProcessing(_)
    }

    def "should update status to processing for pending attachment"() {
        given:
        def message = ["assignedId": "att-789"]
        def pendingAttachment = EmailAttachment.builder()
            .assignedId("att-789")
            .messageId(UUID.randomUUID())
            .status(AttachmentStatus.pending)
            .build()

        def processingAttachment = pendingAttachment.toBuilder()
            .status(AttachmentStatus.processing)
            .build()

        emailAttachmentRepository.findByAssignedId("att-789") >> Optional.of(pendingAttachment)
        emailAttachmentRepository.updateStatusToProcessing("att-789") >> processingAttachment
        emailMessageRepository.findByAttachmentId("att-789") >> Optional.empty()

        when:
        handler.handle(message)

        then:
        1 * emailAttachmentRepository.updateStatusToProcessing("att-789")
    }

    def "should warn when email message not found for attachment"() {
        given:
        def message = ["assignedId": "att-999"]
        def attachment = EmailAttachment.builder()
            .assignedId("att-999")
            .messageId(UUID.randomUUID())
            .status(AttachmentStatus.pending)
            .build()

        emailAttachmentRepository.findByAssignedId("att-999") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-999") >> attachment
        emailMessageRepository.findByAttachmentId("att-999") >> Optional.empty()

        when:
        handler.handle(message)

        then:
        0 * senderValidationService._
    }

    // ==================== Sender Validation - APPROVE Tests ====================

    def "should upload and send to extraction when sender is approved"() {
        given:
        def message = ["assignedId": "att-approved"]
        def messageId = UUID.randomUUID()
        def attachment = createTestAttachment("att-approved", messageId)
        def emailMessage = createTestEmail(messageId, "approved@example.com", "inbox@company.com")

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.APPROVE)
            .message("Approved")
            .build()

        emailAttachmentRepository.findByAssignedId("att-approved") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-approved") >> attachment
        emailMessageRepository.findByAttachmentId("att-approved") >> Optional.of(emailMessage)
        senderValidationService.validateSender("approved@example.com", "inbox@company.com") >> validationResult

        def uploadResult = S3UploadResult.success(
            "s3://bucket/key", "checksum", 100L, "application/pdf",
            [:], "us-east-1", "bucket", "etag", null, "https://s3.amazonaws.com"
        )
        s3StorageService.uploadFile(_) >> uploadResult

        when:
        handler.handle(message)

        then:
        1 * emailAttachmentRepository.updateStatus(_, AttachmentStatus.processing)
        1 * senderNotificationService.sendDocumentReceiptNotification(_)
        1 * messagePublisher.publish("ai-process", { Map msg ->
            msg.assignedId == "att-approved"
        })
    }

    // ==================== Sender Validation - REJECT_GRACE_PERIOD Tests ====================

    def "should upload but not send to extraction when sender rejected within grace period"() {
        given:
        def message = ["assignedId": "att-grace"]
        def messageId = UUID.randomUUID()
        def attachment = createTestAttachment("att-grace", messageId)
        def emailMessage = createTestEmail(messageId, "grace@example.com", "inbox@company.com")

        def scheduledDeletion = OffsetDateTime.now().plusDays(7)
        def validationResult = ValidationResult.builder()
            .action(ValidationAction.REJECT_GRACE_PERIOD)
            .scheduledDeletionAt(scheduledDeletion)
            .message("Rejected but within grace period")
            .build()

        emailAttachmentRepository.findByAssignedId("att-grace") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-grace") >> attachment
        emailMessageRepository.findByAttachmentId("att-grace") >> Optional.of(emailMessage)
        senderValidationService.validateSender("grace@example.com", "inbox@company.com") >> validationResult

        def uploadResult = S3UploadResult.success(
            "s3://bucket/key", "checksum", 100L, "application/pdf",
            [:], "us-east-1", "bucket", "etag", null, "https://s3.amazonaws.com"
        )
        s3StorageService.uploadFile(_) >> uploadResult

        when:
        handler.handle(message)

        then:
        1 * emailAttachmentRepository.updateStatus(_, AttachmentStatus.processing)
        1 * senderNotificationService.sendDocumentReceiptNotification(_)
        0 * messagePublisher.publish("ai-process", _)
    }

    // ==================== Sender Validation - REJECT_BLOCK Tests ====================

    def "should not upload when sender is blocked"() {
        given:
        def message = ["assignedId": "att-blocked"]
        def messageId = UUID.randomUUID()
        def attachment = createTestAttachment("att-blocked", messageId)
        def emailMessage = createTestEmail(messageId, "blocked@example.com", "inbox@company.com")

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.REJECT_BLOCK)
            .message("Sender blocked")
            .build()

        emailAttachmentRepository.findByAssignedId("att-blocked") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-blocked") >> attachment
        emailMessageRepository.findByAttachmentId("att-blocked") >> Optional.of(emailMessage)
        senderValidationService.validateSender("blocked@example.com", "inbox@company.com") >> validationResult

        when:
        handler.handle(message)

        then:
        0 * s3StorageService.uploadFile(_)
        0 * messagePublisher.publish(_, _)
    }

    // ==================== Sender Validation - PENDING Tests ====================

    def "should upload and create pending approval for new sender"() {
        given:
        def message = ["assignedId": "att-pending"]
        def messageId = UUID.randomUUID()
        def attachment = createTestAttachment("att-pending", messageId)
        def emailMessage = createTestEmail(messageId, "new@example.com", "inbox@company.com", 1L)

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.PENDING)
            .companyId(1L)
            .message("New sender, needs approval")
            .build()

        emailAttachmentRepository.findByAssignedId("att-pending") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-pending") >> attachment
        emailMessageRepository.findByAttachmentId("att-pending") >> Optional.of(emailMessage)
        senderValidationService.validateSender("new@example.com", "inbox@company.com") >> validationResult

        def uploadResult = S3UploadResult.success(
            "s3://bucket/key", "checksum", 100L, "application/pdf",
            [:], "us-east-1", "bucket", "etag", null, "https://s3.amazonaws.com"
        )
        s3StorageService.uploadFile(_) >> uploadResult

        when:
        handler.handle(message)

        then:
        1 * emailAttachmentRepository.updateStatus(_, AttachmentStatus.processing)
        1 * senderNotificationService.sendDocumentReceiptNotification(_)
        1 * approvedSenderRepository.insertIfNotExists({ ApprovedSender sender ->
            sender.companyId == 1L &&
            sender.senderIdentifier == "new@example.com" &&
            sender.whitelistType == EmailWhitelistValue.EMAIL &&
            sender.status == SenderApprovalStatus.PENDING
        }) >> true
        1 * senderApprovalNotificationService.sendPendingSenderApprovalNotification("new@example.com", 1L)
        0 * messagePublisher.publish("ai-process", _)
    }

    def "should not send notification if pending approval already exists"() {
        given:
        def message = ["assignedId": "att-existing-pending"]
        def messageId = UUID.randomUUID()
        def attachment = createTestAttachment("att-existing-pending", messageId)
        def emailMessage = createTestEmail(messageId, "existing@example.com", "inbox@company.com", 1L)

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.PENDING)
            .companyId(1L)
            .message("Sender has pending approval")
            .build()

        emailAttachmentRepository.findByAssignedId("att-existing-pending") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-existing-pending") >> attachment
        emailMessageRepository.findByAttachmentId("att-existing-pending") >> Optional.of(emailMessage)
        senderValidationService.validateSender("existing@example.com", "inbox@company.com") >> validationResult

        def uploadResult = S3UploadResult.success(
            "s3://bucket/key", "checksum", 100L, "application/pdf",
            [:], "us-east-1", "bucket", "etag", null, "https://s3.amazonaws.com"
        )
        s3StorageService.uploadFile(_) >> uploadResult

        when:
        handler.handle(message)

        then:
        1 * approvedSenderRepository.insertIfNotExists(_) >> false
        0 * senderApprovalNotificationService.sendPendingSenderApprovalNotification(_, _)
    }

    def "should handle exception when creating pending approval"() {
        given:
        def message = ["assignedId": "att-error"]
        def messageId = UUID.randomUUID()
        def attachment = createTestAttachment("att-error", messageId)
        def emailMessage = createTestEmail(messageId, "error@example.com", "inbox@company.com", 1L)

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.PENDING)
            .companyId(1L)
            .message("New sender, needs approval")
            .build()

        emailAttachmentRepository.findByAssignedId("att-error") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-error") >> attachment
        emailMessageRepository.findByAttachmentId("att-error") >> Optional.of(emailMessage)
        senderValidationService.validateSender("error@example.com", "inbox@company.com") >> validationResult

        def uploadResult = S3UploadResult.success(
            "s3://bucket/key", "checksum", 100L, "application/pdf",
            [:], "us-east-1", "bucket", "etag", null, "https://s3.amazonaws.com"
        )
        s3StorageService.uploadFile(_) >> uploadResult
        approvedSenderRepository.insertIfNotExists(_) >> { throw new RuntimeException("Database error") }

        when:
        handler.handle(message)

        then:
        noExceptionThrown()
        1 * emailAttachmentRepository.updateStatus(_, AttachmentStatus.processing)
    }

    // ==================== S3 Upload Tests ====================

    def "should upload file with metadata and update attachment status"() {
        given:
        def message = ["assignedId": "att-upload"]
        def messageId = UUID.randomUUID()

        // Create temp file for testing
        def tempFile = Files.createTempFile("test", ".pdf")
        Files.write(tempFile, "PDF content".bytes)

        def attachment = EmailAttachment.builder()
            .assignedId("att-upload")
            .messageId(messageId)
            .fileName("document.pdf")
            .contentType("application/pdf")
            .sizeBytes(11L)
            .checksum("abc123")
            .storageUrl("s3://bucket/key")
            .localFilePath(tempFile.toString())
            .status(AttachmentStatus.pending)
            .build()

        def emailMessage = createTestEmail(messageId, "sender@example.com", "inbox@company.com")

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.APPROVE)
            .build()

        emailAttachmentRepository.findByAssignedId("att-upload") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-upload") >> attachment
        emailMessageRepository.findByAttachmentId("att-upload") >> Optional.of(emailMessage)
        senderValidationService.validateSender(_, _) >> validationResult

        def uploadResult = S3UploadResult.success(
            "s3://bucket/key", "checksum", 100L, "application/pdf",
            ["etag": "xyz789"], "us-east-1", "bucket", "xyz789", null, "https://s3.amazonaws.com"
        )

        when:
        handler.handle(message)

        then:
        1 * s3StorageService.uploadFile({ it.assignedId == "att-upload" }) >> uploadResult
        1 * emailAttachmentRepository.updateStatus({ EmailAttachment att ->
            att.status == AttachmentStatus.uploaded &&
            att.region == "us-east-1" &&
            att.endpoint == "https://s3.amazonaws.com"
        }, AttachmentStatus.processing)

        cleanup:
        Files.deleteIfExists(tempFile)
    }

    def "should handle failed upload gracefully"() {
        given:
        def message = ["assignedId": "att-fail"]
        def messageId = UUID.randomUUID()

        def tempFile = Files.createTempFile("test", ".pdf")
        Files.write(tempFile, "content".bytes)

        def attachment = EmailAttachment.builder()
            .assignedId("att-fail")
            .messageId(messageId)
            .fileName("doc.pdf")
            .contentType("application/pdf")
            .sizeBytes(7L)
            .checksum("checksum")
            .storageUrl("s3://bucket/key")
            .localFilePath(tempFile.toString())
            .status(AttachmentStatus.pending)
            .build()

        def emailMessage = createTestEmail(messageId, "sender@example.com", "inbox@company.com")

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.APPROVE)
            .build()

        emailAttachmentRepository.findByAssignedId("att-fail") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-fail") >> attachment
        emailMessageRepository.findByAttachmentId("att-fail") >> Optional.of(emailMessage)
        senderValidationService.validateSender(_, _) >> validationResult

        def uploadResult = S3UploadResult.failure("Upload failed")
        s3StorageService.uploadFile(_) >> uploadResult

        when:
        handler.handle(message)

        then:
        1 * emailAttachmentRepository.updateStatus({ it.status == AttachmentStatus.failed }, AttachmentStatus.processing)
        0 * messagePublisher.publish("ai-process", _)

        cleanup:
        Files.deleteIfExists(tempFile)
    }

    def "should throw exception if ai-process publish fails"() {
        given:
        def message = ["assignedId": "att-publish-fail"]
        def messageId = UUID.randomUUID()

        def tempFile = Files.createTempFile("test", ".pdf")
        Files.write(tempFile, "content".bytes)

        def attachment = EmailAttachment.builder()
            .assignedId("att-publish-fail")
            .messageId(messageId)
            .fileName("doc.pdf")
            .contentType("application/pdf")
            .sizeBytes(7L)
            .checksum("checksum")
            .storageUrl("s3://bucket/key")
            .localFilePath(tempFile.toString())
            .status(AttachmentStatus.pending)
            .build()

        def emailMessage = createTestEmail(messageId, "sender@example.com", "inbox@company.com")

        def validationResult = ValidationResult.builder()
            .action(ValidationAction.APPROVE)
            .build()

        emailAttachmentRepository.findByAssignedId("att-publish-fail") >> Optional.of(attachment)
        emailAttachmentRepository.updateStatusToProcessing("att-publish-fail") >> attachment
        emailMessageRepository.findByAttachmentId("att-publish-fail") >> Optional.of(emailMessage)
        senderValidationService.validateSender(_, _) >> validationResult

        def uploadResult = S3UploadResult.success(
            "s3://bucket/key", "checksum", 100L, "application/pdf",
            [:], "us-east-1", "bucket", "etag", null, "https://s3.amazonaws.com"
        )
        s3StorageService.uploadFile(_) >> uploadResult
        messagePublisher.publish("ai-process", _) >> { throw new RuntimeException("Publish failed") }

        when:
        handler.handle(message)

        then:
        thrown(RuntimeException)

        cleanup:
        Files.deleteIfExists(tempFile)
    }

    // ==================== Helper Methods ====================

    private EmailAttachment createTestAttachment(String assignedId, UUID messageId) {
        def tempFile = Files.createTempFile("test", ".pdf")
        Files.write(tempFile, "content".bytes)

        return EmailAttachment.builder()
            .assignedId(assignedId)
            .messageId(messageId)
            .fileName("test.pdf")
            .contentType("application/pdf")
            .sizeBytes(7L)
            .checksum("checksum")
            .storageUrl("s3://bucket/key")
            .localFilePath(tempFile.toString())
            .status(AttachmentStatus.pending)
            .build()
    }

    private EmailMessage createTestEmail(UUID id, String fromAddress, String toAddress, Long companyId = 1L) {
        return EmailMessage.builder()
            .id(id)
            .fromAddress(fromAddress)
            .toAddress(toAddress)
            .companyId(companyId)
            .subject("Test Subject")
            .bodyText("Test Body")
            .build()
    }
}
