package com.tosspaper.rbac

import com.tosspaper.common.ForbiddenException
import com.tosspaper.generated.model.AuthorizedUser as GeneratedAuthorizedUser
import com.tosspaper.models.domain.AuthorizedUser
import com.tosspaper.models.domain.AuthorizedUser.UserStatus
import com.tosspaper.models.domain.Role
import com.tosspaper.models.exception.BadRequestException
import spock.lang.Specification

import java.time.OffsetDateTime

class AuthorizedUserServiceSpec extends Specification {

    AuthorizedUserRepository authorizedUserRepository
    AuthorizedUserMapper authorizedUserMapper
    CompanyInvitationRepository invitationRepository
    UserRoleCacheService userRoleCacheService
    AuthorizedUserServiceImpl service

    def setup() {
        authorizedUserRepository = Mock()
        authorizedUserMapper = Mock()
        invitationRepository = Mock()
        userRoleCacheService = Mock()
        service = new AuthorizedUserServiceImpl(
            authorizedUserRepository,
            authorizedUserMapper,
            invitationRepository,
            userRoleCacheService
        )
    }

    // ==================== listAuthorizedUsers ====================

    def "listAuthorizedUsers returns paginated list with all fields"() {
        given: "a company ID and query parameters"
            def companyId = 1L
            def limit = 10
            def users = [
                createUser("user-1", companyId, "user1@test.com", Role.ADMIN),
                createUser("user-2", companyId, "user2@test.com", Role.VIEWER)
            ]
            def generatedUsers = [
                createGeneratedUser("user-1", "user1@test.com", "Admin"),
                createGeneratedUser("user-2", "user2@test.com", "Viewer")
            ]

        when: "listing authorized users"
            def result = service.listAuthorizedUsers(companyId, null, null, null, null, limit)

        then: "repository is called with correct query"
            1 * authorizedUserRepository.findByCompanyId(companyId, _ as AuthorizedUserQuery) >> users

        and: "users are mapped to generated model"
            1 * authorizedUserMapper.toGenerated(users[0]) >> generatedUsers[0]
            1 * authorizedUserMapper.toGenerated(users[1]) >> generatedUsers[1]

        and: "result contains all users with pagination"
            with(result) {
                data.size() == 2
                data[0].id == "user-1"
                data[0].email == "user1@test.com"
                data[1].id == "user-2"
                data[1].email == "user2@test.com"
                pagination != null
            }
    }

    def "listAuthorizedUsers generates next cursor when results equal limit"() {
        given: "results that fill the page"
            def companyId = 1L
            def limit = 2
            def users = [
                createUser("user-1", companyId, "aaa@test.com", Role.ADMIN),
                createUser("user-2", companyId, "bbb@test.com", Role.VIEWER)
            ]

        when: "listing users"
            def result = service.listAuthorizedUsers(companyId, null, null, null, null, limit)

        then: "repository returns exactly limit results"
            1 * authorizedUserRepository.findByCompanyId(companyId, _) >> users
            2 * authorizedUserMapper.toGenerated(_) >> new GeneratedAuthorizedUser()

        and: "next cursor is generated from last email"
            result.pagination.cursor != null
    }

    def "listAuthorizedUsers returns null cursor when results less than limit"() {
        given: "results that don't fill the page"
            def companyId = 1L
            def limit = 10
            def users = [createUser("user-1", companyId, "user@test.com", Role.ADMIN)]

        when: "listing users"
            def result = service.listAuthorizedUsers(companyId, null, null, null, null, limit)

        then: "repository returns less than limit"
            1 * authorizedUserRepository.findByCompanyId(companyId, _) >> users
            1 * authorizedUserMapper.toGenerated(_) >> new GeneratedAuthorizedUser()

        and: "no next cursor"
            result.pagination.cursor == null
    }

    def "listAuthorizedUsers decodes cursor when provided"() {
        given: "a valid cursor"
            def companyId = 1L
            def encodedCursor = com.tosspaper.common.CursorUtils.encodeEmailCursor("cursor@test.com")

        when: "listing users with cursor"
            def result = service.listAuthorizedUsers(companyId, null, null, null, encodedCursor, 20)

        then: "repository is called with decoded cursor email"
            1 * authorizedUserRepository.findByCompanyId(companyId, _ as AuthorizedUserQuery) >> { Long cId, AuthorizedUserQuery q ->
                assert q.cursorId == "cursor@test.com"
                return []
            }

        and: "result is returned"
            result.data.isEmpty()
    }

    def "listAuthorizedUsers handles blank cursor as no cursor"() {
        given: "a blank cursor"
            def companyId = 1L

        when: "listing users with blank cursor"
            def result = service.listAuthorizedUsers(companyId, null, null, null, "   ", 20)

        then: "repository is called without cursor"
            1 * authorizedUserRepository.findByCompanyId(companyId, _ as AuthorizedUserQuery) >> { Long cId, AuthorizedUserQuery q ->
                assert q.cursorId == null
                return []
            }

        and: "result is returned"
            result.data.isEmpty()
    }

    // ==================== updateUserRole ====================

    def "updateUserRole updates role and evicts cache"() {
        given: "an existing user"
            def companyId = 1L
            def userId = "user-123"
            def newRoleId = "admin"
            def updatedBy = "admin@test.com"
            def existingUser = createUser(userId, companyId, "user@test.com", Role.VIEWER)
            def updatedUser = createUser(userId, companyId, "user@test.com", Role.ADMIN)
            def generatedUser = createGeneratedUser(userId, "user@test.com", "Admin")

        when: "updating user role"
            def result = service.updateUserRole(companyId, userId, newRoleId, updatedBy)

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> existingUser

        and: "user is saved with new role"
            1 * authorizedUserRepository.save(_ as AuthorizedUser) >> { AuthorizedUser u ->
                assert u.roleId() == "admin"
                assert u.roleName() == "Admin"
                return updatedUser
            }

        and: "cache is evicted"
            1 * userRoleCacheService.evictUserRole("user@test.com", companyId)

        and: "result is mapped"
            1 * authorizedUserMapper.toGenerated(updatedUser) >> generatedUser
            result.id == userId
    }

    def "updateUserRole throws ForbiddenException when user belongs to different company"() {
        given: "a user from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def userId = "user-123"
            def user = createUser(userId, differentCompanyId, "user@test.com", Role.VIEWER)

        when: "updating user role"
            service.updateUserRole(companyId, userId, "admin", "admin@test.com")

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> user

        and: "ForbiddenException is thrown"
            def ex = thrown(ForbiddenException)
            ex.message.contains("does not belong to this company")

        and: "no save or cache eviction"
            0 * authorizedUserRepository.save(_)
            0 * userRoleCacheService.evictUserRole(_, _)
    }

    def "updateUserRole throws BadRequestException for invalid role"() {
        given: "a valid user"
            def companyId = 1L
            def userId = "user-123"
            def user = createUser(userId, companyId, "user@test.com", Role.VIEWER)

        when: "updating with invalid role"
            service.updateUserRole(companyId, userId, "invalid-role", "admin@test.com")

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> user

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("Invalid role ID")

        and: "no save or cache eviction"
            0 * authorizedUserRepository.save(_)
            0 * userRoleCacheService.evictUserRole(_, _)
    }

    // ==================== removeUser ====================

    def "removeUser deletes user and evicts cache"() {
        given: "a non-owner user"
            def companyId = 1L
            def userId = "user-123"
            def user = createUser(userId, companyId, "user@test.com", Role.VIEWER)

        when: "removing user"
            service.removeUser(companyId, userId)

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> user

        and: "cache is evicted before deletion"
            1 * userRoleCacheService.evictUserRole("user@test.com", companyId)

        and: "invitation is deleted"
            1 * invitationRepository.delete(companyId, "user@test.com")

        and: "user is deleted"
            1 * authorizedUserRepository.delete(userId)
    }

    def "removeUser throws ForbiddenException when user belongs to different company"() {
        given: "a user from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def userId = "user-123"
            def user = createUser(userId, differentCompanyId, "user@test.com", Role.VIEWER)

        when: "removing user"
            service.removeUser(companyId, userId)

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> user

        and: "ForbiddenException is thrown"
            def ex = thrown(ForbiddenException)
            ex.message.contains("does not belong to this company")

        and: "no deletion"
            0 * authorizedUserRepository.delete(_)
            0 * invitationRepository.delete(_, _)
            0 * userRoleCacheService.evictUserRole(_, _)
    }

    def "removeUser throws BadRequestException when removing last owner"() {
        given: "the only owner in company"
            def companyId = 1L
            def userId = "owner-123"
            def owner = createUser(userId, companyId, "owner@test.com", Role.OWNER)
            def onlyOwnerList = [owner]

        when: "removing the last owner"
            service.removeUser(companyId, userId)

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> owner

        and: "owner count is checked"
            1 * authorizedUserRepository.findByCompanyId(companyId, _ as AuthorizedUserQuery) >> { Long cId, AuthorizedUserQuery q ->
                assert q.roleId == "owner"
                assert q.status == "enabled"
                return onlyOwnerList
            }

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("at least one owner")

        and: "no deletion"
            0 * authorizedUserRepository.delete(_)
            0 * invitationRepository.delete(_, _)
            0 * userRoleCacheService.evictUserRole(_, _)
    }

    def "removeUser allows removing owner when other owners exist"() {
        given: "multiple owners in company"
            def companyId = 1L
            def userId = "owner-1"
            def owner1 = createUser(userId, companyId, "owner1@test.com", Role.OWNER)
            def owner2 = createUser("owner-2", companyId, "owner2@test.com", Role.OWNER)
            def multipleOwners = [owner1, owner2]

        when: "removing one owner"
            service.removeUser(companyId, userId)

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> owner1

        and: "owner count shows multiple owners"
            1 * authorizedUserRepository.findByCompanyId(companyId, _ as AuthorizedUserQuery) >> multipleOwners

        and: "cache is evicted"
            1 * userRoleCacheService.evictUserRole("owner1@test.com", companyId)

        and: "deletion proceeds"
            1 * invitationRepository.delete(companyId, "owner1@test.com")
            1 * authorizedUserRepository.delete(userId)
    }

    def "removeUser allows removing the only admin (no protection like owners)"() {
        given: "the only admin in company"
            def companyId = 1L
            def userId = "admin-123"
            def admin = createUser(userId, companyId, "admin@test.com", Role.ADMIN)

        when: "removing the only admin"
            service.removeUser(companyId, userId)

        then: "user is fetched"
            1 * authorizedUserRepository.findById(userId) >> admin

        and: "no owner count check (admins are not protected)"
            0 * authorizedUserRepository.findByCompanyId(companyId, _ as AuthorizedUserQuery)

        and: "cache is evicted"
            1 * userRoleCacheService.evictUserRole("admin@test.com", companyId)

        and: "deletion proceeds without protection"
            1 * invitationRepository.delete(companyId, "admin@test.com")
            1 * authorizedUserRepository.delete(userId)
    }

    // ==================== Helper Methods ====================

    private static AuthorizedUser createUser(String id, Long companyId, String email, Role role) {
        new AuthorizedUser(
            id,
            companyId,
            "supabase-user-id",
            email,
            role.id,
            role.displayName,
            UserStatus.ENABLED,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            "system"
        )
    }

    private static GeneratedAuthorizedUser createGeneratedUser(String id, String email, String roleName) {
        def user = new GeneratedAuthorizedUser()
        user.id = id
        user.email = email
        user.roleName = roleName
        return user
    }
}
