package com.tosspaper.emailengine.service.impl

import com.tosspaper.emailengine.repository.ApprovedSenderRepository
import com.tosspaper.emailengine.repository.EmailAttachmentRepository
import com.tosspaper.models.domain.ApprovedSender
import com.tosspaper.models.domain.EmailAttachment
import com.tosspaper.models.enums.EmailApprovalStatus
import com.tosspaper.models.enums.EmailWhitelistValue
import com.tosspaper.models.enums.SenderApprovalStatus
import com.tosspaper.models.exception.ForbiddenException
import com.tosspaper.models.messaging.MessagePublisher
import com.tosspaper.models.service.CompanyLookupService
import com.tosspaper.models.service.EmailDomainService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Tests for EmailApprovalServiceImpl to ensure email approval/rejection
 * workflow handles sender validation, attachment processing, and queue publishing.
 */
class EmailApprovalServiceImplSpec extends Specification {

    EmailAttachmentRepository emailAttachmentRepository = Mock()
    ApprovedSenderRepository approvedSenderRepository = Mock()
    CompanyLookupService companyLookupService = Mock()
    EmailDomainService emailDomainService = Mock()
    MessagePublisher messagePublisher = Mock()

    @Subject
    EmailApprovalServiceImpl service

    def setup() {
        service = new EmailApprovalServiceImpl(
            emailAttachmentRepository,
            approvedSenderRepository,
            companyLookupService,
            emailDomainService,
            messagePublisher
        )
        service.retentionHours = 168 // 7 days
    }

    // ==================== Company Validation Tests ====================

    def "should validate company exists before processing approval"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailDomainService.isBlockedDomain(_) >> false
        approvedSenderRepository.approveDomainAndRestoreThreads(_, _, _) >> 5
        emailAttachmentRepository.findByDomain(_) >> []

        when:
        service.approveSender(1L, "vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        1 * companyLookupService.getCompanyById(1L)
    }

    // ==================== Email Format Validation Tests ====================

    def "should throw exception for invalid sender identifier format"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo

        when:
        service.approveSender(1L, "invalid-format", EmailApprovalStatus.APPROVED, "user-123")

        then:
        def e = thrown(ForbiddenException)
        e.message.contains("Invalid sender identifier format")
    }

    def "should accept valid email address"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        approvedSenderRepository.approveEmailAndRestoreThreads(_) >> 3
        emailAttachmentRepository.findByEmail(_) >> []

        when:
        service.approveSender(1L, "valid@vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        noExceptionThrown()
    }

    def "should accept valid domain"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailDomainService.isBlockedDomain("vendor.com") >> false
        approvedSenderRepository.approveDomainAndRestoreThreads(_, _, _) >> 2
        emailAttachmentRepository.findByDomain(_) >> []

        when:
        service.approveSender(1L, "vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        noExceptionThrown()
    }

    // ==================== Domain Rejection Tests ====================

    def "should reject domain and soft-delete threads"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailDomainService.isBlockedDomain("spam.com") >> false
        approvedSenderRepository.rejectDomainAndSoftDeleteThreads(_, _, _, _) >> 10

        when:
        service.approveSender(1L, "spam.com", EmailApprovalStatus.REJECTED, "user-123")

        then:
        1 * approvedSenderRepository.rejectDomainAndSoftDeleteThreads(
            1L,
            "spam.com",
            "user-123",
            { OffsetDateTime scheduledDeletion ->
                scheduledDeletion.isAfter(OffsetDateTime.now().plusHours(167))
            }
        )
        0 * emailAttachmentRepository._
    }

    def "should reject email and soft-delete threads"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        approvedSenderRepository.rejectEmailAndSoftDeleteThreads(_, _, _, _) >> 5

        when:
        service.approveSender(1L, "spam@example.com", EmailApprovalStatus.REJECTED, "user-123")

        then:
        1 * approvedSenderRepository.rejectEmailAndSoftDeleteThreads(
            1L,
            "spam@example.com",
            "user-123",
            { OffsetDateTime scheduledDeletion ->
                scheduledDeletion.isAfter(OffsetDateTime.now())
            }
        )
    }

    def "should throw exception when rejecting public email domain"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailDomainService.isBlockedDomain("gmail.com") >> true

        when:
        service.approveSender(1L, "gmail.com", EmailApprovalStatus.REJECTED, "user-123")

        then:
        def e = thrown(ForbiddenException)
        e.message.contains("not allowed to reject public email domain")
    }

    // ==================== Email Approval Tests ====================

    def "should approve individual email and restore threads"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        approvedSenderRepository.approveEmailAndRestoreThreads(_) >> 3

        def attachment = EmailAttachment.builder()
            .assignedId("att-1")
            .storageUrl("s3://bucket/key")
            .build()

        emailAttachmentRepository.findByEmail("approved@vendor.com") >> [attachment]

        when:
        service.approveSender(1L, "approved@vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        1 * approvedSenderRepository.approveEmailAndRestoreThreads({ ApprovedSender sender ->
            sender.companyId == 1L &&
            sender.senderIdentifier == "approved@vendor.com" &&
            sender.whitelistType == EmailWhitelistValue.EMAIL &&
            sender.status == SenderApprovalStatus.APPROVED &&
            sender.approvedBy == "user-123" &&
            sender.scheduledDeletionAt == null
        })
    }

    def "should publish attachments to ai-process queue after email approval"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        approvedSenderRepository.approveEmailAndRestoreThreads(_) >> 2

        def att1 = EmailAttachment.builder().assignedId("att-1").storageUrl("s3://bucket/key1").build()
        def att2 = EmailAttachment.builder().assignedId("att-2").storageUrl("s3://bucket/key2").build()

        emailAttachmentRepository.findByEmail("approved@vendor.com") >> [att1, att2]

        when:
        service.approveSender(1L, "approved@vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        1 * messagePublisher.publish("ai-process", { Map msg ->
            msg.assignedId == "att-1" && msg.storageUrl == "s3://bucket/key1"
        })
        1 * messagePublisher.publish("ai-process", { Map msg ->
            msg.assignedId == "att-2" && msg.storageUrl == "s3://bucket/key2"
        })
    }

    def "should not fail approval if no attachments found for email"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        approvedSenderRepository.approveEmailAndRestoreThreads(_) >> 0
        emailAttachmentRepository.findByEmail("approved@vendor.com") >> []

        when:
        service.approveSender(1L, "approved@vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        noExceptionThrown()
        0 * messagePublisher.publish(_, _)
    }

    // ==================== Domain Approval Tests ====================

    def "should approve domain and restore threads"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailDomainService.isBlockedDomain("vendor.com") >> false
        approvedSenderRepository.approveDomainAndRestoreThreads(_, _, _) >> 15
        emailAttachmentRepository.findByDomain(_) >> []

        when:
        service.approveSender(1L, "vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        1 * approvedSenderRepository.approveDomainAndRestoreThreads(1L, "vendor.com", "user-123")
    }

    def "should throw exception when approving blocked domain"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailDomainService.isBlockedDomain("gmail.com") >> true

        when:
        service.approveSender(1L, "gmail.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        def e = thrown(ForbiddenException)
        e.message.contains("not allowed to approve gmail.com")
    }

    def "should publish all domain attachments to ai-process queue"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailDomainService.isBlockedDomain("vendor.com") >> false
        approvedSenderRepository.approveDomainAndRestoreThreads(_, _, _) >> 20

        def att1 = EmailAttachment.builder().assignedId("att-1").storageUrl("s3://bucket/key1").build()
        def att2 = EmailAttachment.builder().assignedId("att-2").storageUrl("s3://bucket/key2").build()
        def att3 = EmailAttachment.builder().assignedId("att-3").storageUrl("s3://bucket/key3").build()

        emailAttachmentRepository.findByDomain("vendor.com") >> [att1, att2, att3]

        when:
        service.approveSender(1L, "vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        3 * messagePublisher.publish("ai-process", _)
    }

    def "should handle publish failure gracefully and continue processing"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo
        approvedSenderRepository.approveEmailAndRestoreThreads(_) >> 2

        def att1 = EmailAttachment.builder().assignedId("att-1").storageUrl("s3://bucket/key1").build()
        def att2 = EmailAttachment.builder().assignedId("att-2").storageUrl("s3://bucket/key2").build()

        emailAttachmentRepository.findByEmail("approved@vendor.com") >> [att1, att2]

        messagePublisher.publish("ai-process", { it.assignedId == "att-1" }) >> { throw new RuntimeException("Queue error") }

        when:
        service.approveSender(1L, "approved@vendor.com", EmailApprovalStatus.APPROVED, "user-123")

        then:
        noExceptionThrown()
        1 * messagePublisher.publish("ai-process", { it.assignedId == "att-2" })
    }

    // ==================== Status Validation Tests ====================

    def "should throw exception for PENDING_APPROVAL status"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyById(1L) >> companyInfo

        when:
        service.approveSender(1L, "sender@vendor.com", EmailApprovalStatus.PENDING_APPROVAL, "user-123")

        then:
        def e = thrown(ForbiddenException)
        e.message.contains("Status not allowed")
    }
}
