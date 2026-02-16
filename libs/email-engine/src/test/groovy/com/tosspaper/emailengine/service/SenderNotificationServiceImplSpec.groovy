package com.tosspaper.emailengine.service

import com.mailgun.api.v3.MailgunMessagesApi
import com.mailgun.model.message.Message
import com.mailgun.model.message.MessageResponse
import com.tosspaper.emailengine.repository.EmailMessageRepository
import com.tosspaper.models.config.MailgunProperties
import com.tosspaper.models.domain.EmailAttachment
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.service.CompanyLookupService
import com.tosspaper.models.service.SyncConflictNotificationRequest
import feign.FeignException
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * Tests for SenderNotificationServiceImpl to ensure email notifications
 * are sent correctly via Mailgun for various scenarios.
 */
class SenderNotificationServiceImplSpec extends Specification {

    MailgunMessagesApi mailgunMessagesApi = Mock()
    MailgunProperties mailgunProperties = Mock()
    DocumentReceiptEmailContentBuilder documentReceiptEmailContentBuilder = Mock()
    NoAttachmentEmailContentBuilder noAttachmentEmailContentBuilder = Mock()
    UnsupportedFileTypeEmailContentBuilder unsupportedFileTypeEmailContentBuilder = Mock()
    ExistingUserInvitationEmailContentBuilder existingUserInvitationEmailContentBuilder = Mock()
    SyncConflictEmailContentBuilder syncConflictEmailContentBuilder = Mock()
    CompanyLookupService companyLookupService = Mock()
    EmailMessageRepository emailMessageRepository = Mock()

    @Subject
    SenderNotificationServiceImpl service

    def setup() {
        service = new SenderNotificationServiceImpl(
            mailgunMessagesApi,
            mailgunProperties,
            documentReceiptEmailContentBuilder,
            noAttachmentEmailContentBuilder,
            unsupportedFileTypeEmailContentBuilder,
            existingUserInvitationEmailContentBuilder,
            syncConflictEmailContentBuilder,
            companyLookupService,
            emailMessageRepository
        )

        // Setup default mailgun properties
        mailgunProperties.getDomain() >> "mg.example.com"
        mailgunProperties.getFromEmail() >> "noreply@example.com"
    }

    // ==================== Document Receipt Notification Tests ====================

    def "should send document receipt notification successfully"() {
        given: "an attachment with message details"
        def messageId = UUID.randomUUID()
        def attachment = EmailAttachment.builder()
            .messageId(messageId)
            .assignedId("att-123")
            .fileName("invoice.pdf")
            .build()

        def emailMessage = EmailMessage.builder()
            .id(messageId)
            .fromAddress("sender@example.com")
            .companyId(1L)
            .providerTimestamp(OffsetDateTime.parse("2024-01-15T10:00:00Z"))
            .build()

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "acme@tosspaper.com", "owner@acme.com", "Acme Corp")

        and: "mocked dependencies"
        emailMessageRepository.findById(messageId) >> emailMessage
        companyLookupService.getCompanyById(1L) >> companyInfo
        documentReceiptEmailContentBuilder.buildSubject("att-123") >> "Document Received - att-123"
        documentReceiptEmailContentBuilder.buildBody("sender@example.com", "att-123", "invoice.pdf", "Acme Corp", _) >> "Email body"

        def messageResponse = GroovyMock(MessageResponse)
        messageResponse.getMessage() >> "mailgun-msg-id-123"

        when: "sending document receipt notification"
        service.sendDocumentReceiptNotification(attachment)

        and: "wait for async execution"
        TimeUnit.MILLISECONDS.sleep(100)

        then: "mailgun API should be called with correct message"
        1 * mailgunMessagesApi.sendMessage("mg.example.com", { Message msg ->
            msg.from == "noreply@example.com" &&
            msg.to.contains("sender@example.com") &&
            msg.subject == "Document Received - att-123" &&
            msg.text == "Email body"
        }) >> messageResponse
    }

    def "should use createdAt if providerTimestamp is null"() {
        given:
        def messageId = UUID.randomUUID()
        def attachment = EmailAttachment.builder()
            .messageId(messageId)
            .assignedId("att-456")
            .fileName("contract.pdf")
            .build()

        def createdAt = OffsetDateTime.parse("2024-01-20T15:00:00Z")
        def emailMessage = EmailMessage.builder()
            .id(messageId)
            .fromAddress("vendor@example.com")
            .companyId(2L)
            .providerTimestamp(null)
            .createdAt(createdAt)
            .build()

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(2L, "test@tosspaper.com", "owner@test.com", "Test Co")

        emailMessageRepository.findById(messageId) >> emailMessage
        companyLookupService.getCompanyById(2L) >> companyInfo
        documentReceiptEmailContentBuilder.buildSubject("att-456") >> "Subject"
        def messageResponse = GroovyMock(MessageResponse)
        mailgunMessagesApi.sendMessage(_, _) >> messageResponse

        when:
        service.sendDocumentReceiptNotification(attachment)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        1 * documentReceiptEmailContentBuilder.buildBody(_, _, _, _, createdAt) >> "Body"
    }

    def "should not throw exception when mailgun authentication fails"() {
        given:
        def messageId = UUID.randomUUID()
        def attachment = EmailAttachment.builder()
            .messageId(messageId)
            .assignedId("att-789")
            .fileName("doc.pdf")
            .build()

        def emailMessage = EmailMessage.builder()
            .id(messageId)
            .fromAddress("sender@example.com")
            .companyId(1L)
            .providerTimestamp(OffsetDateTime.now())
            .build()

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "company@tosspaper.com", "owner@company.com", "Company")

        emailMessageRepository.findById(messageId) >> emailMessage
        companyLookupService.getCompanyById(1L) >> companyInfo
        documentReceiptEmailContentBuilder.buildSubject(_) >> "Subject"
        documentReceiptEmailContentBuilder.buildBody(_, _, _, _, _) >> "Body"
        mailgunMessagesApi.sendMessage(_, _) >> { throw Mock(FeignException.Unauthorized) }

        when:
        service.sendDocumentReceiptNotification(attachment)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        noExceptionThrown()
    }

    def "should not throw exception when generic error occurs"() {
        given:
        def messageId = UUID.randomUUID()
        def attachment = EmailAttachment.builder()
            .messageId(messageId)
            .assignedId("att-999")
            .fileName("file.pdf")
            .build()

        def emailMessage = EmailMessage.builder()
            .id(messageId)
            .fromAddress("sender@example.com")
            .companyId(1L)
            .providerTimestamp(OffsetDateTime.now())
            .build()

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "company@tosspaper.com", "owner@company.com", "Company")

        emailMessageRepository.findById(messageId) >> emailMessage
        companyLookupService.getCompanyById(1L) >> companyInfo
        documentReceiptEmailContentBuilder.buildSubject(_) >> "Subject"
        documentReceiptEmailContentBuilder.buildBody(_, _, _, _, _) >> "Body"
        mailgunMessagesApi.sendMessage(_, _) >> { throw new RuntimeException("Test error") }

        when:
        service.sendDocumentReceiptNotification(attachment)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        noExceptionThrown()
    }

    // ==================== No Attachment Notification Tests ====================

    def "should send no-attachment notification successfully"() {
        given:
        def emailMessage = EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .companyId(1L)
            .providerTimestamp(OffsetDateTime.parse("2024-01-15T10:00:00Z"))
            .build()

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", "owner@acme.com", "Acme Corp")

        companyLookupService.getCompanyById(1L) >> companyInfo
        noAttachmentEmailContentBuilder.buildSubject() >> "No Document Attached"
        noAttachmentEmailContentBuilder.buildBody("sender@example.com", "inbox@company.com", "Acme Corp", _) >> "Body"

        def messageResponse = GroovyMock(MessageResponse)
        messageResponse.getMessage() >> "mailgun-msg-id-456"

        when:
        service.sendNoAttachmentNotification(emailMessage)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", { Message msg ->
            msg.from == "noreply@example.com" &&
            msg.to.contains("sender@example.com") &&
            msg.subject == "No Document Attached"
        }) >> messageResponse
    }

    def "should use createdAt for no-attachment notification if providerTimestamp is null"() {
        given:
        def createdAt = OffsetDateTime.parse("2024-01-20T15:00:00Z")
        def emailMessage = EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .companyId(1L)
            .providerTimestamp(null)
            .createdAt(createdAt)
            .build()

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", "owner@company.com", "Company")

        companyLookupService.getCompanyById(1L) >> companyInfo
        noAttachmentEmailContentBuilder.buildSubject() >> "Subject"
        def messageResponse = GroovyMock(MessageResponse)
        mailgunMessagesApi.sendMessage(_, _) >> messageResponse

        when:
        service.sendNoAttachmentNotification(emailMessage)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        1 * noAttachmentEmailContentBuilder.buildBody(_, _, _, createdAt) >> "Body"
    }

    def "should handle mailgun errors gracefully for no-attachment notification"() {
        given:
        def emailMessage = EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .companyId(1L)
            .providerTimestamp(OffsetDateTime.now())
            .build()

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", "owner@company.com", "Company")

        companyLookupService.getCompanyById(1L) >> companyInfo
        noAttachmentEmailContentBuilder.buildSubject() >> "Subject"
        noAttachmentEmailContentBuilder.buildBody(_, _, _, _) >> "Body"
        mailgunMessagesApi.sendMessage(_, _) >> { throw new RuntimeException("Error") }

        when:
        service.sendNoAttachmentNotification(emailMessage)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        noExceptionThrown()
    }

    // ==================== Unsupported File Type Notification Tests ====================

    def "should send unsupported file type notification successfully"() {
        given:
        def emailMessage = EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .companyId(1L)
            .providerTimestamp(OffsetDateTime.parse("2024-01-15T10:00:00Z"))
            .build()

        def invalidFile1 = FileObject.builder()
            .fileName("document.exe")
            .contentType("application/x-executable")
            .build()

        def invalidFile2 = FileObject.builder()
            .fileName("archive.zip")
            .contentType("application/zip")
            .build()

        def invalidFiles = [invalidFile1, invalidFile2]

        unsupportedFileTypeEmailContentBuilder.buildSubject() >> "Unsupported File Type"
        unsupportedFileTypeEmailContentBuilder.buildBody("sender@example.com", "inbox@company.com", invalidFiles, _) >> "Body"

        def messageResponse = GroovyMock(MessageResponse)
        messageResponse.getMessage() >> "mailgun-msg-id-789"

        when:
        service.sendUnsupportedFileTypeNotification(emailMessage, invalidFiles)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", { Message msg ->
            msg.from == "noreply@example.com" &&
            msg.to.contains("sender@example.com") &&
            msg.subject == "Unsupported File Type"
        }) >> messageResponse
    }

    def "should use createdAt for unsupported file notification if providerTimestamp is null"() {
        given:
        def createdAt = OffsetDateTime.parse("2024-01-20T15:00:00Z")
        def emailMessage = EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .providerTimestamp(null)
            .createdAt(createdAt)
            .build()

        def invalidFile = FileObject.builder()
            .fileName("file.exe")
            .contentType("application/x-executable")
            .build()

        unsupportedFileTypeEmailContentBuilder.buildSubject() >> "Subject"
        def messageResponse = GroovyMock(MessageResponse)
        mailgunMessagesApi.sendMessage(_, _) >> messageResponse

        when:
        service.sendUnsupportedFileTypeNotification(emailMessage, [invalidFile])
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        1 * unsupportedFileTypeEmailContentBuilder.buildBody(_, _, _, createdAt) >> "Body"
    }

    def "should handle errors for unsupported file type notification"() {
        given:
        def emailMessage = EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("sender@example.com")
            .toAddress("inbox@company.com")
            .providerTimestamp(OffsetDateTime.now())
            .build()

        def invalidFile = FileObject.builder().fileName("file.exe").contentType("application/x-executable").build()

        unsupportedFileTypeEmailContentBuilder.buildSubject() >> "Subject"
        unsupportedFileTypeEmailContentBuilder.buildBody(_, _, _, _) >> "Body"
        mailgunMessagesApi.sendMessage(_, _) >> { throw Mock(FeignException.Unauthorized) }

        when:
        service.sendUnsupportedFileTypeNotification(emailMessage, [invalidFile])
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        noExceptionThrown()
    }

    // ==================== Existing User Invitation Notification Tests ====================

    def "should send existing user invitation notification successfully"() {
        given:
        def email = "user@example.com"
        def companyId = 1L
        def companyName = "Acme Corp"
        def roleName = "Admin"

        existingUserInvitationEmailContentBuilder.buildSubject(companyName) >> "You've been invited to join Acme Corp"
        existingUserInvitationEmailContentBuilder.buildBody(email, companyName, roleName, companyId) >> "Invitation body"

        def messageResponse = GroovyMock(MessageResponse)
        messageResponse.getMessage() >> "mailgun-msg-id-invitation"

        when:
        service.sendExistingUserInvitationNotification(email, companyId, companyName, roleName)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", { Message msg ->
            msg.from == "noreply@example.com" &&
            msg.to.contains(email) &&
            msg.subject == "You've been invited to join Acme Corp" &&
            msg.text == "Invitation body"
        }) >> messageResponse
    }

    def "should handle errors for existing user invitation notification"() {
        given:
        existingUserInvitationEmailContentBuilder.buildSubject(_) >> "Subject"
        existingUserInvitationEmailContentBuilder.buildBody(_, _, _, _) >> "Body"
        mailgunMessagesApi.sendMessage(_, _) >> { throw new RuntimeException("Error") }

        when:
        service.sendExistingUserInvitationNotification("user@example.com", 1L, "Company", "Admin")
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        noExceptionThrown()
    }

    // ==================== Sync Conflict Notification Tests ====================

    def "should send sync conflict notification successfully"() {
        given:
        def request = new SyncConflictNotificationRequest(
            1L,                           // companyId
            "QuickBooks",                 // provider
            "Invoice",                    // entityType
            "INV-001",                    // entityName
            "Version conflict detected",  // errorMessage
            "user@example.com"            // updatedBy
        )

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "acme@tosspaper.com", "owner@acme.com", "Acme Corp")

        companyLookupService.getCompanyById(1L) >> companyInfo
        syncConflictEmailContentBuilder.buildSubject("Acme Corp", "QuickBooks", "Invoice", "INV-001") >> "Sync Conflict: Invoice 'INV-001' in QuickBooks"
        syncConflictEmailContentBuilder.buildBody("Acme Corp", request) >> "Conflict details"

        def messageResponse = GroovyMock(MessageResponse)
        messageResponse.getMessage() >> "mailgun-msg-id-sync"

        when:
        service.sendSyncConflictNotification(request)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", { Message msg ->
            msg.from == "noreply@example.com" &&
            msg.to.contains("user@example.com") &&
            msg.subject == "Sync Conflict: Invoice 'INV-001' in QuickBooks"
        }) >> messageResponse
    }

    def "should not send notification if updatedBy is null"() {
        given:
        def request = new SyncConflictNotificationRequest(
            1L, "QuickBooks", "Invoice", "INV-001", "Error", null
        )

        when:
        service.sendSyncConflictNotification(request)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        0 * mailgunMessagesApi.sendMessage(_, _)
    }

    def "should not send notification if updatedBy is blank"() {
        given:
        def request = new SyncConflictNotificationRequest(
            1L, "QuickBooks", "Invoice", "INV-001", "Error", "  "
        )

        when:
        service.sendSyncConflictNotification(request)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        0 * mailgunMessagesApi.sendMessage(_, _)
    }

    def "should handle errors for sync conflict notification"() {
        given:
        def request = new SyncConflictNotificationRequest(
            1L, "QuickBooks", "Invoice", "INV-001", "Error", "user@example.com"
        )

        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "company@tosspaper.com", "owner@company.com", "Company")

        companyLookupService.getCompanyById(1L) >> companyInfo
        syncConflictEmailContentBuilder.buildSubject(_, _, _, _) >> "Subject"
        syncConflictEmailContentBuilder.buildBody(_, _) >> "Body"
        mailgunMessagesApi.sendMessage(_, _) >> { throw Mock(FeignException.Unauthorized) }

        when:
        service.sendSyncConflictNotification(request)
        TimeUnit.MILLISECONDS.sleep(100)

        then:
        noExceptionThrown()
    }
}
