package com.tosspaper.mapper

import com.tosspaper.generated.model.ApprovedSenderResponse
import com.tosspaper.models.domain.ApprovedSender
import com.tosspaper.models.enums.EmailWhitelistValue
import com.tosspaper.models.enums.SenderApprovalStatus
import com.tosspaper.models.service.EmailDomainService
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class ApprovedSenderMapperSpec extends Specification {

    EmailDomainService emailDomainService
    ApprovedSenderMapper mapper

    def setup() {
        emailDomainService = Mock()
        mapper = new ApprovedSenderMapper(emailDomainService)
    }

    // ==================== toApiResponse ====================

    def "toApiResponse maps all fields correctly for email whitelist"() {
        given: "an approved sender with email identifier"
            def now = OffsetDateTime.now()
            def domain = ApprovedSender.builder()
                .id("sender-123")
                .companyId(1L)
                .senderIdentifier("vendor@supplier.com")
                .whitelistType(EmailWhitelistValue.EMAIL)
                .status(SenderApprovalStatus.APPROVED)
                .approvedBy("admin@test.com")
                .approvedAt(now.minusDays(1))
                .updatedAt(now)
                .createdAt(now.minusDays(2))
                .scheduledDeletionAt(null)
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "email domain service is called"
            1 * emailDomainService.isBlockedDomain("supplier.com") >> false

        and: "all fields are mapped correctly"
            result != null
            result.id == "sender-123"
            result.companyId == 1L
            result.senderIdentifier == "vendor@supplier.com"
            result.whitelistType == ApprovedSenderResponse.WhitelistTypeEnum.EMAIL
            result.status == ApprovedSenderResponse.StatusEnum.APPROVED
            result.approvedBy == "admin@test.com"
            result.approvedAt == now.minusDays(1)
            result.updatedAt == now
            result.createdAt == now.minusDays(2)
            result.scheduledDeletionAt == null
            result.domainAccessAllowed == true
    }

    def "toApiResponse maps domain whitelist correctly"() {
        given: "an approved sender with domain identifier"
            def now = OffsetDateTime.now()
            def domain = ApprovedSender.builder()
                .id("sender-456")
                .companyId(2L)
                .senderIdentifier("supplier.com")
                .whitelistType(EmailWhitelistValue.DOMAIN)
                .status(SenderApprovalStatus.APPROVED)
                .approvedBy("owner@test.com")
                .approvedAt(now)
                .updatedAt(now)
                .createdAt(now.minusDays(1))
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "domain is checked for blocking"
            1 * emailDomainService.isBlockedDomain("supplier.com") >> false

        and: "whitelist type is correctly set"
            result.whitelistType == ApprovedSenderResponse.WhitelistTypeEnum.DOMAIN
            result.senderIdentifier == "supplier.com"
            result.domainAccessAllowed == true
    }

    def "toApiResponse sets domainAccessAllowed to false for blocked domains"() {
        given: "an approved sender with blocked domain"
            def domain = ApprovedSender.builder()
                .id("sender-789")
                .companyId(1L)
                .senderIdentifier("test@gmail.com")
                .whitelistType(EmailWhitelistValue.EMAIL)
                .status(SenderApprovalStatus.APPROVED)
                .approvedBy("admin@test.com")
                .approvedAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "email domain service returns blocked"
            1 * emailDomainService.isBlockedDomain("gmail.com") >> true

        and: "domainAccessAllowed is false"
            result.domainAccessAllowed == false
    }

    @Unroll
    def "toApiResponse maps status #status correctly"() {
        given: "an approved sender with specific status"
            def domain = createApprovedSender(status: status)

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "domain check is performed"
            1 * emailDomainService.isBlockedDomain(_) >> false

        and: "status is correctly mapped"
            result.status == expectedApiStatus

        where:
            // Note: OpenAPI StatusEnum only has APPROVED and REJECTED, no PENDING
            status                          || expectedApiStatus
            SenderApprovalStatus.APPROVED   || ApprovedSenderResponse.StatusEnum.APPROVED
            SenderApprovalStatus.REJECTED   || ApprovedSenderResponse.StatusEnum.REJECTED
    }

    def "toApiResponse handles rejected sender with scheduled deletion"() {
        given: "a rejected sender with scheduled deletion"
            def deletionTime = OffsetDateTime.now().plusDays(30)
            def domain = ApprovedSender.builder()
                .id("sender-999")
                .companyId(1L)
                .senderIdentifier("spam@badactor.com")
                .whitelistType(EmailWhitelistValue.EMAIL)
                .status(SenderApprovalStatus.REJECTED)
                .approvedBy("admin@test.com")
                .approvedAt(null)
                .updatedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .scheduledDeletionAt(deletionTime)
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "domain check is performed"
            1 * emailDomainService.isBlockedDomain("badactor.com") >> false

        and: "scheduled deletion is preserved"
            result.status == ApprovedSenderResponse.StatusEnum.REJECTED
            result.scheduledDeletionAt == deletionTime
            result.approvedAt == null
    }

    def "toApiResponse extracts domain from email address"() {
        given: "sender with email containing uppercase domain"
            def domain = createApprovedSender(senderIdentifier: "USER@EXAMPLE.COM")

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "domain is extracted and lowercased"
            1 * emailDomainService.isBlockedDomain("example.com") >> false

        and: "result is successful"
            result.domainAccessAllowed == true
    }

    def "toApiResponse handles domain identifier (not email)"() {
        given: "sender with domain identifier"
            def domain = createApprovedSender(
                senderIdentifier: "SUPPLIER.COM",
                whitelistType: EmailWhitelistValue.DOMAIN
            )

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "domain is lowercased"
            1 * emailDomainService.isBlockedDomain("supplier.com") >> false

        and: "result is successful"
            result.domainAccessAllowed == true
    }

    def "toApiResponse handles email with special characters"() {
        given: "sender with special characters in email"
            def domain = createApprovedSender(senderIdentifier: "first.last+tag@example.co.uk")

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "domain is correctly extracted"
            1 * emailDomainService.isBlockedDomain("example.co.uk") >> false

        and: "result is successful"
            result.senderIdentifier == "first.last+tag@example.co.uk"
    }

    def "toApiResponse handles subdomain email addresses"() {
        given: "sender with subdomain email"
            def domain = createApprovedSender(senderIdentifier: "user@mail.example.com")

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "full subdomain is extracted"
            1 * emailDomainService.isBlockedDomain("mail.example.com") >> false

        and: "result is successful"
            result.domainAccessAllowed == true
    }

    def "toApiResponse handles email with hyphens in domain"() {
        given: "sender with hyphenated domain"
            def domain = createApprovedSender(senderIdentifier: "user@my-company.com")

        when: "mapping to API response"
            def result = mapper.toApiResponse(domain)

        then: "domain with hyphen is extracted"
            1 * emailDomainService.isBlockedDomain("my-company.com") >> false

        and: "result is successful"
            result.domainAccessAllowed == true
    }


    def "toApiResponse creates new instance each time"() {
        given: "a domain sender"
            def domain = createApprovedSender()

        when: "mapping twice"
            def result1 = mapper.toApiResponse(domain)
            def result2 = mapper.toApiResponse(domain)

        then: "domain checks are performed"
            2 * emailDomainService.isBlockedDomain(_) >> false

        and: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
    }

    // ==================== Helper Methods ====================

    private ApprovedSender createApprovedSender(Map overrides = [:]) {
        def now = OffsetDateTime.now()
        def defaults = [
            id: "sender-123",
            companyId: 1L,
            senderIdentifier: "vendor@supplier.com",
            whitelistType: EmailWhitelistValue.EMAIL,
            status: SenderApprovalStatus.APPROVED,
            approvedBy: "admin@test.com",
            approvedAt: now,
            updatedAt: now,
            createdAt: now.minusDays(1),
            scheduledDeletionAt: null
        ]

        def merged = defaults + overrides

        return ApprovedSender.builder()
            .id(merged.id)
            .companyId(merged.companyId)
            .senderIdentifier(merged.senderIdentifier)
            .whitelistType(merged.whitelistType)
            .status(merged.status)
            .approvedBy(merged.approvedBy)
            .approvedAt(merged.approvedAt)
            .updatedAt(merged.updatedAt)
            .createdAt(merged.createdAt)
            .scheduledDeletionAt(merged.scheduledDeletionAt)
            .build()
    }
}
