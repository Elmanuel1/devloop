package com.tosspaper.emailengine.service.impl

import com.tosspaper.emailengine.repository.ApprovedSenderRepository
import com.tosspaper.emailengine.service.dto.ValidationAction
import com.tosspaper.models.domain.ApprovedSender
import com.tosspaper.models.enums.EmailWhitelistValue
import com.tosspaper.models.enums.SenderApprovalStatus
import com.tosspaper.models.service.CompanyLookupService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Tests for SenderValidationServiceImpl to ensure sender validation
 * logic correctly handles approved, rejected, and pending senders.
 */
class SenderValidationServiceImplSpec extends Specification {

    ApprovedSenderRepository approvedSenderRepository = Mock()
    CompanyLookupService companyLookupService = Mock()

    @Subject
    SenderValidationServiceImpl service

    def setup() {
        service = new SenderValidationServiceImpl(approvedSenderRepository, companyLookupService)
    }

    // ==================== Company Lookup Tests ====================

    def "should reject when no company found for recipient email"() {
        given:
        companyLookupService.getCompanyByAssignedEmail("unknown@example.com") >> Optional.empty()

        when:
        def result = service.validateSender("sender@example.com", "unknown@example.com")

        then:
        result.action == ValidationAction.REJECT_BLOCK
        result.message.contains("Invalid recipient address")
    }

    // ==================== Same Domain Auto-Approval Tests ====================

    def "should auto-approve sender from same domain as recipient"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)
        approvedSenderRepository.findByCompanyId(1L) >> []

        when:
        def result = service.validateSender("user@acme.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.APPROVE
        result.companyId == 1L
        result.message.contains("Same domain")
    }

    def "should be case-insensitive when matching same domain"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@ACME.COM")
        companyLookupService.getCompanyByAssignedEmail("inbox@ACME.COM") >> Optional.of(companyInfo)
        approvedSenderRepository.findByCompanyId(1L) >> []

        when:
        def result = service.validateSender("USER@acme.com", "inbox@ACME.COM")

        then:
        result.action == ValidationAction.APPROVE
        result.companyId == 1L
    }

    // ==================== Email-Level Approval Tests ====================

    def "should approve sender with explicitly approved email"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def approvedSender = ApprovedSender.builder()
            .senderIdentifier("approved@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [approvedSender]

        when:
        def result = service.validateSender("approved@vendor.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.APPROVE
        result.companyId == 1L
        result.message.contains("explicitly approved")
    }

    def "should approve email even if domain is rejected (email takes precedence)"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def rejectedDomain = ApprovedSender.builder()
            .senderIdentifier("vendor.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(OffsetDateTime.now().plusDays(7))
            .build()

        def approvedEmail = ApprovedSender.builder()
            .senderIdentifier("special@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [rejectedDomain, approvedEmail]

        when:
        def result = service.validateSender("special@vendor.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.APPROVE
        result.message.contains("explicitly approved")
    }

    // ==================== Email-Level Rejection Tests ====================

    def "should reject sender with explicitly rejected email within grace period"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def scheduledDeletion = OffsetDateTime.now().plusDays(3)
        def rejectedSender = ApprovedSender.builder()
            .senderIdentifier("rejected@spam.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(scheduledDeletion)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [rejectedSender]

        when:
        def result = service.validateSender("rejected@spam.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.REJECT_GRACE_PERIOD
        result.companyId == 1L
        result.scheduledDeletionAt == scheduledDeletion
    }

    def "should reject sender with explicitly rejected email past grace period"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def scheduledDeletion = OffsetDateTime.now().minusDays(1)
        def rejectedSender = ApprovedSender.builder()
            .senderIdentifier("blocked@spam.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(scheduledDeletion)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [rejectedSender]

        when:
        def result = service.validateSender("blocked@spam.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.REJECT_BLOCK
        result.companyId == 1L
    }

    def "should block rejected email with no scheduled deletion"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def rejectedSender = ApprovedSender.builder()
            .senderIdentifier("rejected@spam.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(null)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [rejectedSender]

        when:
        def result = service.validateSender("rejected@spam.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.REJECT_BLOCK
    }

    def "should reject email even if domain is approved (email takes precedence)"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def approvedDomain = ApprovedSender.builder()
            .senderIdentifier("vendor.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        def rejectedEmail = ApprovedSender.builder()
            .senderIdentifier("spam@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(OffsetDateTime.now().plusDays(7))
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [approvedDomain, rejectedEmail]

        when:
        def result = service.validateSender("spam@vendor.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.REJECT_GRACE_PERIOD
    }

    // ==================== Domain-Level Approval Tests ====================

    def "should approve sender from approved domain"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def approvedDomain = ApprovedSender.builder()
            .senderIdentifier("vendor.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [approvedDomain]

        when:
        def result = service.validateSender("user@vendor.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.APPROVE
        result.companyId == 1L
        result.message.contains("domain is approved")
    }

    def "should be case-insensitive when matching domain approval"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def approvedDomain = ApprovedSender.builder()
            .senderIdentifier("VENDOR.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [approvedDomain]

        when:
        def result = service.validateSender("user@vendor.COM", "inbox@acme.com")

        then:
        result.action == ValidationAction.APPROVE
    }

    // ==================== Domain-Level Rejection Tests ====================

    def "should reject sender from rejected domain within grace period"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def scheduledDeletion = OffsetDateTime.now().plusDays(5)
        def rejectedDomain = ApprovedSender.builder()
            .senderIdentifier("spam.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(scheduledDeletion)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [rejectedDomain]

        when:
        def result = service.validateSender("anyone@spam.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.REJECT_GRACE_PERIOD
        result.scheduledDeletionAt == scheduledDeletion
    }

    def "should block sender from rejected domain past grace period"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def scheduledDeletion = OffsetDateTime.now().minusDays(2)
        def rejectedDomain = ApprovedSender.builder()
            .senderIdentifier("blocked.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(scheduledDeletion)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [rejectedDomain]

        when:
        def result = service.validateSender("anyone@blocked.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.REJECT_BLOCK
    }

    // ==================== Pending Sender Tests ====================

    def "should return pending for sender with existing pending approval"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def pendingSender = ApprovedSender.builder()
            .senderIdentifier("pending@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.PENDING)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [pendingSender]

        when:
        def result = service.validateSender("pending@vendor.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.PENDING
        result.companyId == 1L
        result.message.contains("pending approval")
    }

    def "should return pending for new sender not in any list"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        approvedSenderRepository.findByCompanyId(1L) >> []

        when:
        def result = service.validateSender("new@vendor.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.PENDING
        result.companyId == 1L
        result.message.contains("New sender")
    }

    // ==================== Complex Scenario Tests ====================

    def "should handle multiple senders with different statuses"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        def approvedEmail = ApprovedSender.builder()
            .senderIdentifier("approved@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        def rejectedEmail = ApprovedSender.builder()
            .senderIdentifier("rejected@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(OffsetDateTime.now().plusDays(7))
            .build()

        def pendingEmail = ApprovedSender.builder()
            .senderIdentifier("pending@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.PENDING)
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [approvedEmail, rejectedEmail, pendingEmail]

        when: "validating approved sender"
        def approvedResult = service.validateSender("approved@vendor.com", "inbox@acme.com")

        then:
        approvedResult.action == ValidationAction.APPROVE

        when: "validating rejected sender"
        def rejectedResult = service.validateSender("rejected@vendor.com", "inbox@acme.com")

        then:
        rejectedResult.action == ValidationAction.REJECT_GRACE_PERIOD

        when: "validating pending sender"
        def pendingResult = service.validateSender("pending@vendor.com", "inbox@acme.com")

        then:
        pendingResult.action == ValidationAction.PENDING
    }

    def "should prioritize email-level rules over domain-level rules"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        // Domain is approved
        def approvedDomain = ApprovedSender.builder()
            .senderIdentifier("vendor.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        // But this specific email is rejected
        def rejectedEmail = ApprovedSender.builder()
            .senderIdentifier("bad@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.REJECTED)
            .scheduledDeletionAt(OffsetDateTime.now().minusDays(1))
            .build()

        approvedSenderRepository.findByCompanyId(1L) >> [approvedDomain, rejectedEmail]

        when: "validating the rejected email"
        def result = service.validateSender("bad@vendor.com", "inbox@acme.com")

        then: "email-level rejection takes precedence"
        result.action == ValidationAction.REJECT_BLOCK
    }

    def "should handle empty sender list for company"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        approvedSenderRepository.findByCompanyId(1L) >> []

        when:
        def result = service.validateSender("anyone@vendor.com", "inbox@acme.com")

        then:
        result.action == ValidationAction.PENDING
        result.companyId == 1L
    }

    def "should handle null domain extraction gracefully"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "Acme Corp", "owner@acme.com", "inbox@acme.com")
        companyLookupService.getCompanyByAssignedEmail("inbox@acme.com") >> Optional.of(companyInfo)

        approvedSenderRepository.findByCompanyId(1L) >> []

        when:
        service.validateSender("invalid-email", "inbox@acme.com")

        then:
        thrown(IllegalArgumentException)
    }
}
