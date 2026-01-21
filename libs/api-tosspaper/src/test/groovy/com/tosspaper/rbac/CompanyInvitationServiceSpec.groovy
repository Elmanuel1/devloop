package com.tosspaper.rbac

import com.tosspaper.common.BadRequestException
import com.tosspaper.common.DuplicateException
import com.tosspaper.company.CompanyRepository
import com.tosspaper.generated.model.CompanyInvitation
import com.tosspaper.models.domain.AuthorizedUser
import com.tosspaper.models.domain.CompanyInvitation as DomainCompanyInvitation
import com.tosspaper.models.domain.Role
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import com.tosspaper.models.service.EmailDomainService
import com.tosspaper.models.service.SenderNotificationService
import com.tosspaper.supabase.AuthInvitationClient
import com.tosspaper.supabase.UserAlreadyExistsException
import org.jooq.DSLContext
import spock.lang.Specification

import java.time.OffsetDateTime

class CompanyInvitationServiceSpec extends Specification {

    CompanyInvitationRepository invitationRepository
    InvitationMapper invitationMapper
    AuthInvitationClient authInvitationClient
    AuthorizedUserRepository authorizedUserRepository
    EmailDomainService emailDomainService
    SenderNotificationService senderNotificationService
    CompanyRepository companyRepository
    DSLContext dslContext
    CompanyInvitationServiceImpl service

    def setup() {
        invitationRepository = Mock()
        invitationMapper = Mock()
        authInvitationClient = Mock()
        authorizedUserRepository = Mock()
        emailDomainService = Mock()
        senderNotificationService = Mock()
        companyRepository = Mock()
        dslContext = Mock()
        service = new CompanyInvitationServiceImpl(
            invitationRepository,
            invitationMapper,
            authInvitationClient,
            authorizedUserRepository,
            emailDomainService,
            senderNotificationService,
            companyRepository,
            dslContext
        )
    }

    // ==================== listCompanyInvitations ====================

    def "listCompanyInvitations returns paginated list"() {
        given: "invitations exist for company"
            def companyId = 1L
            def invitations = [
                createDomainInvitation(companyId, "user1@test.com", "admin"),
                createDomainInvitation(companyId, "user2@test.com", "viewer")
            ]
            def generatedInvitation1 = createGeneratedInvitation("user1@test.com", "Admin")
            def generatedInvitation2 = createGeneratedInvitation("user2@test.com", "Viewer")

        when: "listing invitations"
            def result = service.listCompanyInvitations(companyId, null, null, null, 20)

        then: "repository is called"
            1 * invitationRepository.findByCompanyId(companyId, _ as CompanyInvitationQuery) >> invitations

        and: "invitations are mapped"
            1 * invitationMapper.toGenerated(invitations[0]) >> generatedInvitation1
            1 * invitationMapper.toGenerated(invitations[1]) >> generatedInvitation2

        and: "result contains invitations"
            with(result) {
                data.size() == 2
                data[0].email == "user1@test.com"
                data[1].email == "user2@test.com"
            }
    }

    def "listCompanyInvitations generates cursor when results equal limit"() {
        given: "results that fill the page"
            def companyId = 1L
            def limit = 2
            def invitations = [
                createDomainInvitation(companyId, "aaa@test.com", "admin"),
                createDomainInvitation(companyId, "bbb@test.com", "viewer")
            ]

        when: "listing invitations"
            def result = service.listCompanyInvitations(companyId, null, null, null, limit)

        then: "repository returns exactly limit results"
            1 * invitationRepository.findByCompanyId(companyId, _) >> invitations
            2 * invitationMapper.toGenerated(_) >> new CompanyInvitation()

        and: "next cursor is generated"
            result.pagination.cursor != null
    }

    def "listCompanyInvitations filters by email and status"() {
        given: "filter parameters"
            def companyId = 1L
            def email = "test@example.com"
            def status = "invited"

        when: "listing invitations with filters"
            service.listCompanyInvitations(companyId, email, status, null, 20)

        then: "repository is called with filters"
            1 * invitationRepository.findByCompanyId(companyId, _ as CompanyInvitationQuery) >> { Long cId, CompanyInvitationQuery q ->
                assert q.email == email
                assert q.status == status
                return []
            }
    }

    // ==================== sendInvitation ====================

    def "sendInvitation creates invitation and sends email"() {
        given: "a valid invitation request"
            def companyId = 1L
            def email = "newuser@business.com"
            def roleId = "admin"

            def savedInvitation = createDomainInvitation(companyId, email, roleId)
            def generatedInvitation = createGeneratedInvitation(email, "Admin")

        when: "sending invitation"
            def result = service.sendInvitation(companyId, email, roleId)

        then: "email domain is validated"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "user is not already a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "no existing invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "invitation is saved"
            1 * invitationRepository.save(_ as DomainCompanyInvitation) >> savedInvitation

        and: "Supabase invitation is sent"
            1 * authInvitationClient.inviteUserByEmail(email, _)

        and: "result is mapped"
            1 * invitationMapper.toGenerated(savedInvitation) >> generatedInvitation

        and: "result has correct fields"
            with(result) {
                it.email == email
            }
    }

    def "sendInvitation throws BadRequestException for invalid role"() {
        given: "an invalid role ID"
            def companyId = 1L
            def email = "user@business.com"
            def invalidRoleId = "superuser"

        when: "sending invitation"
            service.sendInvitation(companyId, email, invalidRoleId)

        then: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("Invalid role ID")
    }

    def "sendInvitation throws BadRequestException for blocked email domain"() {
        given: "a personal email address"
            def companyId = 1L
            def email = "user@gmail.com"
            def roleId = "admin"

        when: "sending invitation"
            service.sendInvitation(companyId, email, roleId)

        then: "domain is blocked"
            1 * emailDomainService.isBlockedDomain("gmail.com") >> true

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("personal email")
    }

    def "sendInvitation throws DuplicateException when user is already a member"() {
        given: "a user who is already a member"
            def companyId = 1L
            def email = "existing@business.com"
            def roleId = "admin"
            def existingUser = AuthorizedUser.builder()
                    .id("user-1")
                    .companyId(companyId)
                    .email(email)
                    .roleId("viewer")
                    .build()

        when: "sending invitation"
            service.sendInvitation(companyId, email, roleId)

        then: "domain is valid"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "user is already a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingUser)

        and: "DuplicateException is thrown"
            def ex = thrown(DuplicateException)
            ex.message.contains("already a member")
    }

    def "sendInvitation throws DuplicateException when invitation already exists"() {
        given: "an existing pending invitation"
            def companyId = 1L
            def email = "invited@business.com"
            def roleId = "admin"
            def existingInvitation = createDomainInvitation(companyId, email, roleId)

        when: "sending invitation"
            service.sendInvitation(companyId, email, roleId)

        then: "domain is valid"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "user is not a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "invitation already exists"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingInvitation)

        and: "DuplicateException is thrown"
            def ex = thrown(DuplicateException)
            ex.message.contains("already sent")
    }

    def "sendInvitation sends login email when user already exists in Supabase"() {
        given: "an existing Supabase user"
            def companyId = 1L
            def email = "existing@business.com"
            def roleId = "viewer"

            def savedInvitation = createDomainInvitation(companyId, email, roleId)
            def generatedInvitation = createGeneratedInvitation(email, "Viewer")
            def companyRecord = new CompaniesRecord()
            companyRecord.name = "Test Company"

        when: "sending invitation"
            def result = service.sendInvitation(companyId, email, roleId)

        then: "domain is valid"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "user is not a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "no existing invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "invitation is saved"
            1 * invitationRepository.save(_ as DomainCompanyInvitation) >> savedInvitation

        and: "Supabase throws UserAlreadyExistsException"
            1 * authInvitationClient.inviteUserByEmail(email, _) >> { throw new UserAlreadyExistsException(email, "User exists") }

        and: "company is fetched for email"
            1 * companyRepository.findById(companyId) >> companyRecord

        and: "existing user notification is sent"
            1 * senderNotificationService.sendExistingUserInvitationNotification(email, companyId, "Test Company", "Viewer")

        and: "result is mapped"
            1 * invitationMapper.toGenerated(savedInvitation) >> generatedInvitation

        and: "result is returned"
            result.email == email
    }

    // ==================== cancelInvitation ====================

    def "cancelInvitation deletes invitation"() {
        given: "an existing invitation"
            def companyId = 1L
            def email = "invited@test.com"

        when: "cancelling invitation"
            service.cancelInvitation(companyId, email)

        then: "invitation is deleted"
            1 * invitationRepository.delete(companyId, email)
    }

    def "sendInvitation throws DuplicateException when invitation was already accepted"() {
        given: "an existing accepted invitation"
            def companyId = 1L
            def email = "accepted@business.com"
            def roleId = "admin"
            def existingInvitation = DomainCompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId(roleId)
                .status(DomainCompanyInvitation.InvitationStatus.ACCEPTED)
                .createdAt(OffsetDateTime.now())
                .build()

        when: "sending invitation"
            service.sendInvitation(companyId, email, roleId)

        then: "domain is valid"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "user is not a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "invitation exists with ACCEPTED status"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingInvitation)

        and: "DuplicateException is thrown"
            def ex = thrown(DuplicateException)
            ex.message.contains("already accepted")
    }

    def "sendInvitation deletes invitation and throws when Supabase fails"() {
        given: "a valid invitation request"
            def companyId = 1L
            def email = "newuser@business.com"
            def roleId = "admin"

            def savedInvitation = createDomainInvitation(companyId, email, roleId)

        when: "sending invitation"
            service.sendInvitation(companyId, email, roleId)

        then: "email domain is validated"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "user is not already a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "no existing invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "invitation is saved"
            1 * invitationRepository.save(_ as DomainCompanyInvitation) >> savedInvitation

        and: "Supabase throws a generic exception"
            1 * authInvitationClient.inviteUserByEmail(email, _) >> { throw new RuntimeException("Connection failed") }

        and: "invitation is deleted from DB"
            1 * invitationRepository.delete(companyId, email)

        and: "RuntimeException is thrown"
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to send invitation email")
    }

    def "listCompanyInvitations decodes cursor when provided"() {
        given: "a valid cursor"
            def companyId = 1L
            def encodedCursor = com.tosspaper.common.CursorUtils.encodeEmailCursor("cursor@test.com")

        when: "listing invitations with cursor"
            service.listCompanyInvitations(companyId, null, null, encodedCursor, 20)

        then: "repository is called with decoded cursor email"
            1 * invitationRepository.findByCompanyId(companyId, _ as CompanyInvitationQuery) >> { Long cId, CompanyInvitationQuery q ->
                assert q.cursorId == "cursor@test.com"
                return []
            }
    }

    def "listCompanyInvitations handles blank cursor as no cursor"() {
        given: "a blank cursor"
            def companyId = 1L

        when: "listing invitations with blank cursor"
            service.listCompanyInvitations(companyId, null, null, "   ", 20)

        then: "repository is called without cursor"
            1 * invitationRepository.findByCompanyId(companyId, _ as CompanyInvitationQuery) >> { Long cId, CompanyInvitationQuery q ->
                assert q.cursorId == null
                return []
            }
    }

    // ==================== acceptInvitationByCode ====================

    def "acceptInvitationByCode throws BadRequestException for invalid code"() {
        given: "an invalid invitation code"
            def invalidCode = "not-a-valid-base64-code"

        when: "accepting invitation"
            service.acceptInvitationByCode(invalidCode)

        then: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("Invalid invitation code")
    }

    def "acceptInvitationByCode throws BadRequestException when invitation not found"() {
        given: "a valid invitation code for non-existent invitation"
            def companyId = 1L
            def email = "notfound@test.com"
            def validCode = com.tosspaper.models.util.InvitationCodeUtils.encode(companyId, email)

        when: "accepting invitation"
            service.acceptInvitationByCode(validCode)

        then: "repository returns empty"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("Invitation not found")
    }

    def "acceptInvitationByCode returns existing invitation when already accepted"() {
        given: "an already accepted invitation"
            def companyId = 1L
            def email = "accepted@test.com"
            def validCode = com.tosspaper.models.util.InvitationCodeUtils.encode(companyId, email)
            def existingInvitation = DomainCompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId("admin")
                .status(DomainCompanyInvitation.InvitationStatus.ACCEPTED)
                .createdAt(OffsetDateTime.now())
                .build()
            def generatedInvitation = createGeneratedInvitation(email, "Admin")

        when: "accepting invitation"
            def result = service.acceptInvitationByCode(validCode)

        then: "repository returns accepted invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingInvitation)

        and: "invitation is mapped and returned (idempotent)"
            1 * invitationMapper.toGenerated(existingInvitation) >> generatedInvitation

        and: "result is returned"
            result.email == email
    }

    def "acceptInvitationByCode throws BadRequestException when invitation is declined"() {
        given: "a declined invitation"
            def companyId = 1L
            def email = "declined@test.com"
            def validCode = com.tosspaper.models.util.InvitationCodeUtils.encode(companyId, email)
            def existingInvitation = DomainCompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId("admin")
                .status(DomainCompanyInvitation.InvitationStatus.DECLINED)
                .createdAt(OffsetDateTime.now())
                .build()

        when: "accepting invitation"
            service.acceptInvitationByCode(validCode)

        then: "repository returns declined invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingInvitation)

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("declined")
    }

    def "acceptInvitationByCode throws BadRequestException when invitation is expired"() {
        given: "an expired invitation"
            def companyId = 1L
            def email = "expired@test.com"
            def validCode = com.tosspaper.models.util.InvitationCodeUtils.encode(companyId, email)
            def existingInvitation = DomainCompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId("admin")
                .status(DomainCompanyInvitation.InvitationStatus.INVITED)
                .createdAt(OffsetDateTime.now().minusDays(2))
                .expiresAt(OffsetDateTime.now().minusDays(1)) // Already expired
                .build()

        when: "accepting invitation"
            service.acceptInvitationByCode(validCode)

        then: "repository returns expired invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingInvitation)

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("expired")
    }

    def "acceptInvitationByCode throws BadRequestException when user is already a member"() {
        given: "a valid invitation but user is already a member"
            def companyId = 1L
            def email = "existing@test.com"
            def validCode = com.tosspaper.models.util.InvitationCodeUtils.encode(companyId, email)
            def existingInvitation = DomainCompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId("admin")
                .status(DomainCompanyInvitation.InvitationStatus.INVITED)
                .createdAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .build()
            def existingUser = AuthorizedUser.builder()
                .id("user-1")
                .companyId(companyId)
                .email(email)
                .roleId("viewer")
                .build()

        when: "accepting invitation"
            service.acceptInvitationByCode(validCode)

        then: "repository returns valid invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingInvitation)

        and: "user is already a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingUser)

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("expired")
    }

    def "acceptInvitationByCode successfully accepts valid invitation"() {
        given: "a valid invitation"
            def companyId = 1L
            def email = "newuser@test.com"
            def validCode = com.tosspaper.models.util.InvitationCodeUtils.encode(companyId, email)
            def existingInvitation = DomainCompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId("admin")
                .roleName("Admin")
                .status(DomainCompanyInvitation.InvitationStatus.INVITED)
                .createdAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusHours(24))
                .build()
            def generatedInvitation = createGeneratedInvitation(email, "Admin")
            def userId = "supabase-user-id"

        when: "accepting invitation"
            def result = service.acceptInvitationByCode(validCode)

        then: "repository returns valid invitation"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(existingInvitation)

        and: "user is not already a member"
            1 * authorizedUserRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "user ID is fetched from Supabase"
            1 * authInvitationClient.getUserIdByEmail(email) >> userId

        and: "transaction is executed"
            1 * dslContext.transaction(_) >> { args ->
                // Execute the transaction callback
                def transactionCallback = args[0]
                def mockConfiguration = Mock(org.jooq.Configuration)
                def mockDsl = Mock(DSLContext)
                mockConfiguration.dsl() >> mockDsl
                transactionCallback.run(mockConfiguration)
            }

        and: "authorized user is saved"
            1 * authorizedUserRepository.save(_, _ as AuthorizedUser)

        and: "invitation is updated"
            1 * invitationRepository.save(_, _ as DomainCompanyInvitation)

        and: "result is mapped"
            1 * invitationMapper.toGenerated(_ as DomainCompanyInvitation) >> generatedInvitation

        and: "result is returned"
            result.email == email
    }

    // ==================== Helper Methods ====================

    private DomainCompanyInvitation createDomainInvitation(Long companyId, String email, String roleId) {
        DomainCompanyInvitation.builder()
            .companyId(companyId)
            .email(email)
            .roleId(roleId)
            .roleName(Role.fromId(roleId).map { it.displayName }.orElse("Unknown"))
            .status(DomainCompanyInvitation.InvitationStatus.INVITED)
            .createdAt(OffsetDateTime.now())
            .expiresAt(OffsetDateTime.now().plusHours(24))
            .build()
    }

    private static CompanyInvitation createGeneratedInvitation(String email, String roleName) {
        def invitation = new CompanyInvitation()
        invitation.email = email
        invitation.roleName = roleName
        return invitation
    }
}
