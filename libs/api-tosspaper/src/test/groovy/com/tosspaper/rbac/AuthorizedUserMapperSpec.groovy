package com.tosspaper.rbac

import com.tosspaper.generated.model.AuthorizedUser as GeneratedAuthorizedUser
import com.tosspaper.generated.model.RoleIdEnum
import com.tosspaper.models.domain.AuthorizedUser
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class AuthorizedUserMapperSpec extends Specification {

    AuthorizedUserMapper mapper

    def setup() {
        mapper = new AuthorizedUserMapper()
    }

    // ==================== toGenerated ====================

    def "toGenerated returns null when domain is null"() {
        when: "mapping null domain"
            def result = mapper.toGenerated(null)

        then: "result is null"
            result == null
    }

    def "toGenerated maps all fields correctly"() {
        given: "a complete domain authorized user"
            def createdAt = OffsetDateTime.now().minusDays(10)
            def updatedAt = OffsetDateTime.now()
            def domain = new AuthorizedUser(
                "auth-user-123",
                1L,
                "supabase-user-456",
                "user@company.com",
                "admin",
                "Admin",
                AuthorizedUser.UserStatus.ENABLED,
                createdAt,
                updatedAt,
                "system@tosspaper.com"
            )

        when: "mapping to generated model"
            def result = mapper.toGenerated(domain)

        then: "all fields are mapped correctly"
            result != null
            result.id == "auth-user-123"
            result.companyId == 1L
            result.userId == "supabase-user-456"
            result.email == "user@company.com"
            result.roleId == RoleIdEnum.ADMIN
            result.roleName == "Admin"
            result.status == GeneratedAuthorizedUser.StatusEnum.ENABLED
            result.createdAt == createdAt
            result.updatedAt == updatedAt
            result.lastUpdatedBy == "system@tosspaper.com"
    }

    @Unroll
    def "toGenerated maps role #roleId correctly"() {
        given: "domain user with specific role"
            def domain = createDomainUser(roleId: roleId, roleName: roleName)

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
        given: "domain user with specific status"
            def domain = createDomainUser(status: status)

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "status is correctly mapped"
            result.status == expectedStatusEnum

        where:
            status                              || expectedStatusEnum
            AuthorizedUser.UserStatus.ENABLED   || GeneratedAuthorizedUser.StatusEnum.ENABLED
            AuthorizedUser.UserStatus.DISABLED  || GeneratedAuthorizedUser.StatusEnum.DISABLED
    }

    def "toGenerated handles owner role"() {
        given: "owner user"
            def domain = createDomainUser(
                roleId: "owner",
                roleName: "Owner"
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "owner role is correctly mapped"
            result.roleId == RoleIdEnum.OWNER
            result.roleName == "Owner"
    }

    def "toGenerated handles operations role"() {
        given: "operations user"
            def domain = createDomainUser(
                roleId: "operations",
                roleName: "Operations"
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "operations role is correctly mapped"
            result.roleId == RoleIdEnum.OPERATIONS
            result.roleName == "Operations"
    }

    def "toGenerated handles viewer role"() {
        given: "viewer user"
            def domain = createDomainUser(
                roleId: "viewer",
                roleName: "Viewer"
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "viewer role is correctly mapped"
            result.roleId == RoleIdEnum.VIEWER
            result.roleName == "Viewer"
    }

    def "toGenerated handles disabled user"() {
        given: "disabled user"
            def domain = createDomainUser(status: AuthorizedUser.UserStatus.DISABLED)

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "disabled status is correctly mapped"
            result.status == GeneratedAuthorizedUser.StatusEnum.DISABLED
    }

    def "toGenerated preserves email format"() {
        given: "user with various email formats"
            def domain = createDomainUser(email: "first.last+tag@mail.example.com")

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "email format is preserved"
            result.email == "first.last+tag@mail.example.com"
    }

    def "toGenerated preserves timestamp precision"() {
        given: "user with precise timestamps"
            def createdAt = OffsetDateTime.parse("2024-01-15T10:30:45.123456789Z")
            def updatedAt = OffsetDateTime.parse("2024-01-20T14:25:30.987654321Z")
            def domain = createDomainUser(
                createdAt: createdAt,
                updatedAt: updatedAt
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "timestamp precision is preserved"
            result.createdAt == createdAt
            result.updatedAt == updatedAt
    }

    def "toGenerated handles user updated by system"() {
        given: "user last updated by system"
            def domain = createDomainUser(lastUpdatedBy: "system@tosspaper.com")

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "lastUpdatedBy is preserved"
            result.lastUpdatedBy == "system@tosspaper.com"
    }

    def "toGenerated handles user updated by admin"() {
        given: "user last updated by admin"
            def domain = createDomainUser(lastUpdatedBy: "admin@company.com")

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "lastUpdatedBy is preserved"
            result.lastUpdatedBy == "admin@company.com"
    }

    def "toGenerated handles different company IDs"() {
        given: "users from different companies"
            def user1 = createDomainUser(companyId: 1L)
            def user2 = createDomainUser(companyId: 999L)

        when: "mapping to generated"
            def result1 = mapper.toGenerated(user1)
            def result2 = mapper.toGenerated(user2)

        then: "company IDs are preserved"
            result1.companyId == 1L
            result2.companyId == 999L
    }

    def "toGenerated creates new instance each time"() {
        given: "a domain user"
            def domain = createDomainUser()

        when: "mapping twice"
            def result1 = mapper.toGenerated(domain)
            def result2 = mapper.toGenerated(domain)

        then: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
    }

    def "toGenerated handles all role combinations with enabled status"() {
        given: "users with different roles, all enabled"
            def users = [
                createDomainUser(roleId: "owner", roleName: "Owner"),
                createDomainUser(roleId: "admin", roleName: "Admin"),
                createDomainUser(roleId: "operations", roleName: "Operations"),
                createDomainUser(roleId: "viewer", roleName: "Viewer")
            ]

        when: "mapping all users"
            def results = users.collect { mapper.toGenerated(it) }

        then: "all have enabled status"
            results.every { it.status == GeneratedAuthorizedUser.StatusEnum.ENABLED }
    }

    def "toGenerated preserves UUID format for user IDs"() {
        given: "user with UUID-like IDs"
            def domain = createDomainUser(
                id: "00000000-0000-0000-0000-000000000001",
                userId: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "UUID formats are preserved"
            result.id == "00000000-0000-0000-0000-000000000001"
            result.userId == "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }

    def "toGenerated handles recently created user"() {
        given: "user created recently with same timestamps"
            def now = OffsetDateTime.now()
            def domain = createDomainUser(
                createdAt: now,
                updatedAt: now
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "timestamps are equal"
            result.createdAt == result.updatedAt
    }

    def "toGenerated handles user with old creation date"() {
        given: "user created long ago"
            def createdAt = OffsetDateTime.now().minusYears(5)
            def updatedAt = OffsetDateTime.now()
            def domain = createDomainUser(
                createdAt: createdAt,
                updatedAt: updatedAt
            )

        when: "mapping to generated"
            def result = mapper.toGenerated(domain)

        then: "old creation date is preserved"
            result.createdAt == createdAt
            result.updatedAt.isAfter(result.createdAt)
    }

    // ==================== Helper Methods ====================

    private AuthorizedUser createDomainUser(Map overrides = [:]) {
        def now = OffsetDateTime.now()
        def defaults = [
            id: "auth-user-123",
            companyId: 1L,
            userId: "supabase-user-456",
            email: "user@company.com",
            roleId: "admin",
            roleName: "Admin",
            status: AuthorizedUser.UserStatus.ENABLED,
            createdAt: now.minusDays(10),
            updatedAt: now,
            lastUpdatedBy: "system@tosspaper.com"
        ]

        def merged = defaults + overrides

        return new AuthorizedUser(
            merged.id,
            merged.companyId,
            merged.userId,
            merged.email,
            merged.roleId,
            merged.roleName,
            merged.status,
            merged.createdAt,
            merged.updatedAt,
            merged.lastUpdatedBy
        )
    }
}
