package com.tosspaper.rbac

import com.tosspaper.generated.model.CompanyInvitation as GeneratedCompanyInvitation
import com.tosspaper.generated.model.RoleIdEnum
import com.tosspaper.models.domain.CompanyInvitation
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class InvitationMapperSpec extends Specification {

    InvitationMapper mapper

    def setup() {
        mapper = new InvitationMapper()
    }

    // ==================== toGenerated ====================

    def "toGenerated returns null when domain is null"() {
        when: "mapping null domain"
            def result = mapper.toGenerated(null)

        then: "result is null"
            result == null
    }

    def "toGenerated maps all fields correctly"() {
        given: "a complete domain invitation"
            def createdAt = OffsetDateTime.now().minusHours(2)
            def updatedAt = OffsetDateTime.now().minusHours(1)
            def expiresAt = OffsetDateTime.now().plusHours(22)
            def domain = new CompanyInvitation(
                1L,
                "newuser@company.com",
                "admin",
                "Admin",
                CompanyInvitation.InvitationStatus.INVITED,
                createdAt,
                updatedAt,
                expiresAt
            )

        when: "mapping to generated model"
            def result = mapper.toGenerated(domain)

        then: "all fields are mapped correctly"
            result != null
            result.companyId == 1L
            result.email == "newuser@company.com"
            result.roleId == RoleIdEnum.ADMIN
            result.roleName == "Admin"
            result.status == GeneratedCompanyInvitation.StatusEnum.INVITED
            result.createdAt == createdAt
            result.updatedAt == updatedAt
            result.expiresAt == expiresAt
    }

    @Unroll
    def "toGenerated maps role #roleId correctly"() {
        given: "domain invitation with specific role"
            def domain = createDomainInvitation(roleId: roleId, roleName: roleName)

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "role is correctly mapped"
            result.roleId == expectedRoleEnum
            result.roleName == roleName

        where:
            roleId       | roleName      || expectedRoleEnum
            "owner"      | "Owner"       || RoleIdEnum.OWNER
            "admin"      | "Admin"       || RoleIdEnum.ADMIN
            "operations" | "Operations"  || RoleIdEnum.OPERATIONS
            "viewer"     | "Viewer"      || RoleIdEnum.VIEWER
    }

    @Unroll
    def "toGenerated maps status #status correctly"() {
        given: "domain invitation with specific status"
            def domain = createDomainInvitation(status: status)

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "status is correctly mapped"
            result.status == expectedStatusEnum

        where:
            status                                      || expectedStatusEnum
            CompanyInvitation.InvitationStatus.INVITED  || GeneratedCompanyInvitation.StatusEnum.INVITED
            CompanyInvitation.InvitationStatus.ACCEPTED || GeneratedCompanyInvitation.StatusEnum.ACCEPTED
            CompanyInvitation.InvitationStatus.DECLINED || GeneratedCompanyInvitation.StatusEnum.DECLINED
    }

    def "toGenerated handles owner role invitation"() {
        given: "owner invitation"
            def domain = createDomainInvitation(
                roleId: "owner",
                roleName: "Owner"
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "owner role is correctly mapped"
            result.roleId == RoleIdEnum.OWNER
            result.roleName == "Owner"
    }

    def "toGenerated handles operations role invitation"() {
        given: "operations invitation"
            def domain = createDomainInvitation(
                roleId: "operations",
                roleName: "Operations"
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "operations role is correctly mapped"
            result.roleId == RoleIdEnum.OPERATIONS
            result.roleName == "Operations"
    }

    def "toGenerated handles viewer role invitation"() {
        given: "viewer invitation"
            def domain = createDomainInvitation(
                roleId: "viewer",
                roleName: "Viewer"
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "viewer role is correctly mapped"
            result.roleId == RoleIdEnum.VIEWER
            result.roleName == "Viewer"
    }

    def "toGenerated handles accepted invitation"() {
        given: "accepted invitation"
            def domain = createDomainInvitation(
                status: CompanyInvitation.InvitationStatus.ACCEPTED
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "accepted status is correctly mapped"
            result.status == GeneratedCompanyInvitation.StatusEnum.ACCEPTED
    }

    def "toGenerated handles declined invitation"() {
        given: "declined invitation"
            def domain = createDomainInvitation(
                status: CompanyInvitation.InvitationStatus.DECLINED
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "declined status is correctly mapped"
            result.status == GeneratedCompanyInvitation.StatusEnum.DECLINED
    }

    def "toGenerated preserves email format"() {
        given: "invitation with various email formats"
            def domain = createDomainInvitation(email: "first.last+tag@mail.example.com")

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "email format is preserved"
            result.email == "first.last+tag@mail.example.com"
    }

    def "toGenerated preserves timestamp precision"() {
        given: "invitation with precise timestamps"
            def createdAt = OffsetDateTime.parse("2024-01-15T10:30:45.123456789Z")
            def updatedAt = OffsetDateTime.parse("2024-01-15T11:25:30.987654321Z")
            def expiresAt = OffsetDateTime.parse("2024-01-16T10:30:45.123456789Z")
            def domain = createDomainInvitation(
                createdAt: createdAt,
                updatedAt: updatedAt,
                expiresAt: expiresAt
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "timestamp precision is preserved"
            result.createdAt == createdAt
            result.updatedAt == updatedAt
            result.expiresAt == expiresAt
    }

    def "toGenerated handles invitation expiring soon"() {
        given: "invitation expiring in 1 hour"
            def now = OffsetDateTime.now()
            def domain = createDomainInvitation(
                createdAt: now.minusHours(23),
                expiresAt: now.plusHours(1)
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "expiry time is preserved"
            result.expiresAt.isAfter(OffsetDateTime.now())
            result.expiresAt.isBefore(OffsetDateTime.now().plusHours(2))
    }

    def "toGenerated handles recently created invitation"() {
        given: "invitation created just now"
            def now = OffsetDateTime.now()
            def domain = createDomainInvitation(
                createdAt: now,
                updatedAt: now,
                expiresAt: now.plusHours(24)
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "timestamps are correct"
            result.createdAt == result.updatedAt
            result.expiresAt.isAfter(result.createdAt)
    }

    def "toGenerated handles different company IDs"() {
        given: "invitations from different companies"
            def invitation1 = createDomainInvitation(companyId: 1L)
            def invitation2 = createDomainInvitation(companyId: 999L)

        when: "mapping to generated"
            def result1 = mapper.toGenerated(invitation1)
            def result2 = mapper.toGenerated(invitation2)

        then: "company IDs are preserved"
            result1.companyId == 1L
            result2.companyId == 999L
    }

    def "toGenerated creates new instance each time"() {
        given: "a domain invitation"
            def domain = createDomainInvitation()

        when: "mapping twice"
            def result1 = mapper.toGenerated(domain)
            def result2 = mapper.toGenerated(domain)

        then: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
    }

    def "toGenerated handles all role combinations with invited status"() {
        given: "invitations with different roles"
            def invitations = [
                createDomainInvitation(roleId: "owner", roleName: "Owner"),
                createDomainInvitation(roleId: "admin", roleName: "Admin"),
                createDomainInvitation(roleId: "operations", roleName: "Operations"),
                createDomainInvitation(roleId: "viewer", roleName: "Viewer")
            ]

        when: "mapping all invitations"
            def results = invitations.collect { mapper.toGenerated(it) }

        then: "all have invited status"
            results.every { it.status == GeneratedCompanyInvitation.StatusEnum.INVITED }
    }

    def "toGenerated handles invitation with uppercase email"() {
        given: "invitation with uppercase email"
            def domain = createDomainInvitation(email: "USER@COMPANY.COM")

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "email case is preserved (normalization happens elsewhere)"
            result.email == "USER@COMPANY.COM"
    }

    def "toGenerated handles invitation created and updated at same time"() {
        given: "invitation not yet updated"
            def timestamp = OffsetDateTime.now()
            def domain = createDomainInvitation(
                createdAt: timestamp,
                updatedAt: timestamp
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "timestamps are equal"
            result.createdAt == result.updatedAt
    }

    def "toGenerated handles 24-hour expiry window"() {
        given: "invitation with standard 24h expiry"
            def createdAt = OffsetDateTime.now()
            def expiresAt = createdAt.plusHours(24)
            def domain = createDomainInvitation(
                createdAt: createdAt,
                expiresAt: expiresAt
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "24-hour expiry is preserved"
            result.expiresAt == result.createdAt.plusHours(24)
    }

    def "toGenerated handles invitation for international email"() {
        given: "invitation with international domain"
            def domain = createDomainInvitation(email: "user@company.co.uk")

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "international domain is preserved"
            result.email == "user@company.co.uk"
    }

    def "toGenerated handles invitation updated after creation"() {
        given: "invitation that was updated"
            def createdAt = OffsetDateTime.now().minusHours(10)
            def updatedAt = OffsetDateTime.now().minusHours(5)
            def domain = createDomainInvitation(
                createdAt: createdAt,
                updatedAt: updatedAt
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "update timestamp is after creation"
            result.updatedAt.isAfter(result.createdAt)
    }

    // ==================== Helper Methods ====================

    private CompanyInvitation createDomainInvitation(Map overrides = [:]) {
        def now = OffsetDateTime.now()
        def defaults = [
            companyId: 1L,
            email: "newuser@company.com",
            roleId: "admin",
            roleName: "Admin",
            status: CompanyInvitation.InvitationStatus.INVITED,
            createdAt: now.minusHours(2),
            updatedAt: now.minusHours(2),
            expiresAt: now.plusHours(22)
        ]

        def merged = defaults + overrides

        return new CompanyInvitation(
            merged.companyId,
            merged.email,
            merged.roleId,
            merged.roleName,
            merged.status,
            merged.createdAt,
            merged.updatedAt,
            merged.expiresAt
        )
    }
}
