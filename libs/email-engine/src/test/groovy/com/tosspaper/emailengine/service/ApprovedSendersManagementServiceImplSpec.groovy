package com.tosspaper.emailengine.service

import com.tosspaper.emailengine.repository.ApprovedSenderRepository
import com.tosspaper.models.domain.ApprovedSender
import com.tosspaper.models.domain.PendingSenderApproval
import com.tosspaper.models.enums.EmailWhitelistValue
import com.tosspaper.models.enums.SenderApprovalStatus
import com.tosspaper.models.paging.Paginated
import com.tosspaper.models.paging.Pagination
import com.tosspaper.models.service.CompanyLookupService
import spock.lang.Specification
import spock.lang.Subject

class ApprovedSendersManagementServiceImplSpec extends Specification {

    ApprovedSenderRepository approvedSenderRepository = Mock()
    CompanyLookupService companyLookupService = Mock()

    @Subject
    ApprovedSendersManagementServiceImpl service

    def setup() {
        service = new ApprovedSendersManagementServiceImpl(approvedSenderRepository, companyLookupService)
    }

    def "should list approved senders with pagination"() {
        given:
        def sender1 = ApprovedSender.builder().senderIdentifier("user@vendor.com").build()
        def sender2 = ApprovedSender.builder().senderIdentifier("another@vendor.com").build()
        def pagination = new Pagination(1, 10, 1, 2)
        def paginated = new Paginated<ApprovedSender>([sender1, sender2], pagination)

        approvedSenderRepository.findByCompanyIdAndStatus(1L, 1, 10, SenderApprovalStatus.APPROVED) >> paginated

        when:
        def result = service.listApprovedSenders(1L, SenderApprovalStatus.APPROVED, 1, 10)

        then:
        result.data().size() == 2
        result.pagination().totalItems() == 2
    }

    def "should list pending documents for company"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@acme.com", "owner@acme.com", "Acme")
        companyLookupService.getCompanyById(1L) >> companyInfo

        def pending1 = PendingSenderApproval.builder().senderIdentifier("sender1@vendor.com").documentsPending(3).build()
        def pending2 = PendingSenderApproval.builder().senderIdentifier("sender2@vendor.com").documentsPending(1).build()

        approvedSenderRepository.findPendingDocumentsGroupedBySender(1L, "inbox@acme.com") >> [pending1, pending2]

        when:
        def result = service.listPendingDocuments(1L)

        then:
        result.size() == 2
        result[0].senderIdentifier == "sender1@vendor.com"
        result[0].documentsPending == 3
    }

    def "should update approved sender with email type"() {
        given:
        def updatedSender = ApprovedSender.builder()
            .companyId(1L)
            .senderIdentifier("updated@vendor.com")
            .whitelistType(EmailWhitelistValue.EMAIL)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        when:
        def result = service.updateApprovedSender(1L, "sender-id-123", "updated@vendor.com", EmailWhitelistValue.EMAIL)

        then:
        1 * approvedSenderRepository.upsert({ ApprovedSender sender ->
            sender.companyId == 1L &&
            sender.senderIdentifier == "updated@vendor.com" &&
            sender.whitelistType == EmailWhitelistValue.EMAIL &&
            sender.status == SenderApprovalStatus.APPROVED
        }) >> updatedSender

        result.senderIdentifier == "updated@vendor.com"
    }

    def "should update approved sender with domain type and extract domain"() {
        given:
        def updatedSender = ApprovedSender.builder()
            .companyId(1L)
            .senderIdentifier("vendor.com")
            .whitelistType(EmailWhitelistValue.DOMAIN)
            .status(SenderApprovalStatus.APPROVED)
            .build()

        approvedSenderRepository.upsert(_) >> updatedSender

        when:
        def result = service.updateApprovedSender(1L, "sender-id-456", "user@vendor.com", EmailWhitelistValue.DOMAIN)

        then:
        1 * approvedSenderRepository.upsert({ ApprovedSender sender ->
            sender.senderIdentifier == "vendor.com" &&
            sender.whitelistType == EmailWhitelistValue.DOMAIN
        })
    }

    def "should remove approved sender"() {
        when:
        service.removeApprovedSender(1L, "sender-id-789")

        then:
        1 * approvedSenderRepository.delete("sender-id-789", 1L)
    }
}
