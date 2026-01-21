package com.tosspaper.rbac

import com.tosspaper.models.domain.AuthorizedUser
import spock.lang.Specification

import java.time.OffsetDateTime

class UserRoleCacheServiceSpec extends Specification {

    AuthorizedUserRepository authorizedUserRepository
    UserRoleCacheService service

    def setup() {
        authorizedUserRepository = Mock()
        service = new UserRoleCacheService(authorizedUserRepository)
    }

    // ==================== getUserRole ====================

    def "getUserRole returns role when user exists and is enabled"() {
        given: "an enabled user"
            def email = "user@test.com"
            def companyId = 1L
            def user = createUser(email, companyId, "admin", AuthorizedUser.UserStatus.ENABLED)

        when: "fetching user role"
            def result = service.getUserRole(email, companyId)

        then: "repository is queried"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(user)

        and: "role is returned"
            result == "admin"
    }

    def "getUserRole returns null when user not found"() {
        given: "non-existent user"
            def email = "unknown@test.com"
            def companyId = 1L

        when: "fetching user role"
            def result = service.getUserRole(email, companyId)

        then: "repository returns empty"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "null is returned"
            result == null
    }

    def "getUserRole returns null when user is disabled"() {
        given: "a disabled user"
            def email = "disabled@test.com"
            def companyId = 1L
            def user = createUser(email, companyId, "admin", AuthorizedUser.UserStatus.DISABLED)

        when: "fetching user role"
            def result = service.getUserRole(email, companyId)

        then: "repository returns disabled user"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(user)

        and: "null is returned (disabled users filtered out)"
            result == null
    }

    def "getUserRole returns correct role for different roles"() {
        given: "a user with specific role"
            def email = "user@test.com"
            def companyId = 1L
            def user = createUser(email, companyId, roleId, AuthorizedUser.UserStatus.ENABLED)

        when: "fetching user role"
            def result = service.getUserRole(email, companyId)

        then: "repository is queried"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(user)

        and: "correct role is returned"
            result == roleId

        where:
            roleId << ["owner", "admin", "operations", "viewer"]
    }

    // ==================== evictUserRole ====================

    def "evictUserRole logs eviction"() {
        given: "user details"
            def email = "user@test.com"
            def companyId = 1L

        when: "evicting user role"
            service.evictUserRole(email, companyId)

        then: "no exception thrown (cache eviction is handled by Spring)"
            noExceptionThrown()
    }

    // ==================== evictAllUserRoles ====================

    def "evictAllUserRoles logs eviction"() {
        given: "user email"
            def email = "user@test.com"

        when: "evicting all user roles"
            service.evictAllUserRoles(email)

        then: "no exception thrown"
            noExceptionThrown()
    }

    // ==================== Helper Methods ====================

    private AuthorizedUser createUser(String email, Long companyId, String roleId, AuthorizedUser.UserStatus status) {
        new AuthorizedUser(
            "user-id",
            companyId,
            "supabase-id",
            email,
            roleId,
            roleId.capitalize(),
            status,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            "system"
        )
    }
}
