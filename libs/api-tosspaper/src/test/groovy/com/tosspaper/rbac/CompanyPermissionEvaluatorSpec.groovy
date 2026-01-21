package com.tosspaper.rbac

import com.tosspaper.models.domain.PermissionRegistry
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import spock.lang.Specification

class CompanyPermissionEvaluatorSpec extends Specification {

    UserRoleCacheService userRoleCacheService
    CompanyPermissionEvaluator evaluator

    def setup() {
        userRoleCacheService = Mock()
        evaluator = new CompanyPermissionEvaluator(userRoleCacheService)
    }

    // ==================== hasPermission(Authentication, Object, Object) ====================

    def "hasPermission returns false when authentication is null"() {
        when: "checking permission with null authentication"
            def result = evaluator.hasPermission(null, 1L, "companies:view")

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    def "hasPermission returns false when authentication is not authenticated"() {
        given: "an unauthenticated authentication object"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> false

        when: "checking permission"
            def result = evaluator.hasPermission(authentication, 1L, "companies:view")

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    def "hasPermission returns false when targetDomainObject is null"() {
        given: "a valid authentication"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true

        when: "checking permission with null target"
            def result = evaluator.hasPermission(authentication, null, "companies:view")

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    def "hasPermission returns false when permission is null"() {
        given: "a valid authentication"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true

        when: "checking permission with null permission"
            def result = evaluator.hasPermission(authentication, 1L, null)

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    def "hasPermission returns false when email cannot be extracted"() {
        given: "an authentication without email"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> null

        when: "checking permission"
            def result = evaluator.hasPermission(authentication, 1L, "companies:view")

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    def "hasPermission returns false when email is empty"() {
        given: "an authentication with empty email"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> ""

        when: "checking permission"
            def result = evaluator.hasPermission(authentication, 1L, "companies:view")

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    def "hasPermission returns false when user has no role for company"() {
        given: "an authenticated user with email"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "user@test.com"

        when: "checking permission"
            def result = evaluator.hasPermission(authentication, 1L, "companies:view")

        then: "role cache returns null"
            1 * userRoleCacheService.getUserRole("user@test.com", 1L) >> null

        and: "returns false"
            !result
    }

    def "hasPermission returns true when user has required permission"() {
        given: "an authenticated admin user"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "admin@test.com"

        when: "checking permission that admin has"
            def result = evaluator.hasPermission(authentication, 1L, "companies:view")

        then: "role cache returns admin role"
            1 * userRoleCacheService.getUserRole("admin@test.com", 1L) >> "admin"

        and: "returns true (admin has companies:view permission)"
            result
    }

    def "hasPermission returns false when user lacks required permission"() {
        given: "an authenticated viewer user"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "viewer@test.com"

        when: "checking permission that viewer doesn't have"
            def result = evaluator.hasPermission(authentication, 1L, "members:edit")

        then: "role cache returns viewer role"
            1 * userRoleCacheService.getUserRole("viewer@test.com", 1L) >> "viewer"

        and: "returns false (viewer doesn't have members:edit permission)"
            !result
    }

    def "hasPermission extracts email from OAuth2User"() {
        given: "an OAuth2 authenticated user"
            def attributes = [email: "oauth@test.com", sub: "user-id"]
            def user = new DefaultOAuth2User([], attributes, "sub")
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> user

        when: "checking permission"
            def result = evaluator.hasPermission(authentication, 1L, "companies:view")

        then: "email is extracted from OAuth2User attributes"
            1 * userRoleCacheService.getUserRole("oauth@test.com", 1L) >> "owner"

        and: "returns true"
            result
    }

    def "hasPermission falls back to getName when OAuth2User has no email attribute"() {
        given: "an OAuth2 user without email attribute"
            def attributes = [sub: "user-id"]
            def user = new DefaultOAuth2User([], attributes, "sub")
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> user
            authentication.getName() >> "fallback@test.com"

        when: "checking permission"
            def result = evaluator.hasPermission(authentication, 1L, "companies:view")

        then: "email is extracted from getName()"
            1 * userRoleCacheService.getUserRole("fallback@test.com", 1L) >> "admin"

        and: "returns true"
            result
    }

    def "hasPermission parses company ID from string"() {
        given: "an authenticated user"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "user@test.com"

        when: "checking permission with string company ID"
            def result = evaluator.hasPermission(authentication, "42", "companies:view")

        then: "company ID is parsed to Long"
            1 * userRoleCacheService.getUserRole("user@test.com", 42L) >> "owner"

        and: "returns true"
            result
    }

    // ==================== hasPermission(Authentication, Serializable, String, Object) ====================

    def "hasPermission with targetType returns false when targetId is null"() {
        given: "a valid authentication"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true

        when: "checking permission with null targetId"
            def result = evaluator.hasPermission(authentication, null, "company", "companies:view")

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    def "hasPermission with company targetType delegates to main method"() {
        given: "an authenticated user"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "user@test.com"

        when: "checking permission with 'company' targetType"
            def result = evaluator.hasPermission(authentication, 1L, "company", "companies:view")

        then: "delegates to main hasPermission method"
            1 * userRoleCacheService.getUserRole("user@test.com", 1L) >> "admin"

        and: "returns true"
            result
    }

    def "hasPermission with unsupported targetType returns false"() {
        given: "an authenticated user"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "user@test.com"

        when: "checking permission with unsupported targetType"
            def result = evaluator.hasPermission(authentication, 1L, "project", "projects:view")

        then: "returns false"
            !result

        and: "no cache lookup"
            0 * userRoleCacheService.getUserRole(_, _)
    }

    // ==================== Permission combinations ====================

    def "hasPermission correctly checks owner permissions"() {
        given: "an authenticated owner"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "owner@test.com"

        when: "checking various permissions"
            def companyId = 1L
            userRoleCacheService.getUserRole("owner@test.com", companyId) >> "owner"

        then: "owner has all permissions"
            evaluator.hasPermission(authentication, companyId, "companies:view")
            evaluator.hasPermission(authentication, companyId, "companies:edit")
            evaluator.hasPermission(authentication, companyId, "members:edit")
    }

    def "hasPermission correctly checks viewer permissions"() {
        given: "an authenticated viewer"
            def authentication = Mock(Authentication)
            authentication.isAuthenticated() >> true
            authentication.getPrincipal() >> "not-an-oauth2-user"
            authentication.getName() >> "viewer@test.com"

        when: "checking various permissions"
            def companyId = 1L
            userRoleCacheService.getUserRole("viewer@test.com", companyId) >> "viewer"

        then: "viewer has limited permissions"
            evaluator.hasPermission(authentication, companyId, "companies:view")
            !evaluator.hasPermission(authentication, companyId, "members:edit")
    }
}
