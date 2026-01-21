package com.tosspaper.rbac

import com.tosspaper.common.NotFoundException
import com.tosspaper.company.CompanyMapper
import com.tosspaper.company.CompanyRepository
import com.tosspaper.generated.model.Company
import com.tosspaper.models.domain.AuthorizedUser
import com.tosspaper.models.domain.CompanyInvitation
import com.tosspaper.models.domain.Role
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import org.jooq.Configuration
import org.jooq.DSLContext
import spock.lang.Specification

import java.time.OffsetDateTime

class UserOnboardingServiceSpec extends Specification {

    CompanyRepository companyRepository
    CompanyMapper companyMapper
    AuthorizedUserRepository authorizedUserRepository
    CompanyInvitationRepository invitationRepository
    DSLContext dslContext
    UserOnboardingService service

    def setup() {
        companyRepository = Mock()
        companyMapper = Mock()
        authorizedUserRepository = Mock()
        invitationRepository = Mock()
        dslContext = Mock()
        service = new UserOnboardingService(
            companyRepository,
            companyMapper,
            authorizedUserRepository,
            invitationRepository,
            dslContext
        )
    }

    // ==================== createCompanyWithOwner ====================

    def "createCompanyWithOwner creates company and owner in transaction"() {
        given: "user registration data"
            def email = "john@acme.com"
            def userId = "supabase-user-123"
            def companyName = "Acme Corp"
            def countryOfIncorporation = "US"

            def companyRecord = new CompaniesRecord()
            companyRecord.id = 1L
            companyRecord.name = companyName

            def company = new Company()
            company.id = 1L
            company.name = companyName

        when: "creating company with owner"
            def result = service.createCompanyWithOwner(email, userId, companyName, countryOfIncorporation)

        then: "transaction is executed"
            1 * dslContext.transactionResult(_) >> { args ->
                // Execute the transaction
                def transactional = args[0]
                def mockTx = Mock(Configuration) {
                    dsl() >> dslContext
                }
                return transactional.run(mockTx)
            }

        and: "company is mapped and saved"
            1 * companyMapper.toRecord(_, email) >> companyRecord
            1 * companyRepository.save(dslContext, companyRecord) >> companyRecord
            1 * companyMapper.toDto(companyRecord) >> company

        and: "authorized user is created as owner"
            1 * authorizedUserRepository.save(dslContext, _ as AuthorizedUser) >> { DSLContext ctx, AuthorizedUser user ->
                assert user.companyId() == 1L
                assert user.userId() == userId
                assert user.email() == email
                assert user.roleId() == Role.OWNER.id
                assert user.roleName() == Role.OWNER.displayName
                assert user.status() == AuthorizedUser.UserStatus.ENABLED
                return user
            }

        and: "result is the created company"
            result.id == 1L
            result.name == companyName
    }

    def "createCompanyWithOwner generates correct assigned email format"() {
        given: "user with business email"
            def email = "john@acme.com"
            def userId = "supabase-user-123"
            def companyName = "Acme Corp"

            def companyRecord = new CompaniesRecord()
            companyRecord.id = 1L

            def company = new Company()
            company.id = 1L

        when: "creating company"
            service.createCompanyWithOwner(email, userId, companyName, null)

        then: "company create request does not set assigned email in webhook onboarding flow"
            1 * companyMapper.toRecord(_, email) >> { create, userEmail ->
                assert create.assignedEmail == null
                return companyRecord
            }

        and: "other mocks"
            1 * dslContext.transactionResult(_) >> { args -> args[0].run(Mock(Configuration) { dsl() >> dslContext }) }
            1 * companyRepository.save(_, _) >> companyRecord
            1 * companyMapper.toDto(_) >> company
            1 * authorizedUserRepository.save(_, _)
    }

    def "createCompanyWithOwner handles null country of incorporation"() {
        given: "user without country specified"
            def email = "john@acme.com"
            def userId = "supabase-user-123"
            def companyName = "Acme Corp"

            def companyRecord = new CompaniesRecord()
            companyRecord.id = 1L

            def company = new Company()
            company.id = 1L

        when: "creating company without country"
            service.createCompanyWithOwner(email, userId, companyName, null)

        then: "mocks"
            1 * dslContext.transactionResult(_) >> { args -> args[0].run(Mock(Configuration) { dsl() >> dslContext }) }
            1 * companyMapper.toRecord(_, _) >> companyRecord
            1 * companyRepository.save(_, _) >> companyRecord
            1 * companyMapper.toDto(_) >> company
            1 * authorizedUserRepository.save(_, _)
    }

    // ==================== acceptInvitationAndCreateAuthorizedUser ====================

    def "acceptInvitationAndCreateAuthorizedUser creates user and accepts invitation"() {
        given: "an existing invitation"
            def companyId = 1L
            def email = "invited@business.com"
            def userId = "supabase-user-456"
            def invitation = CompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId("admin")
                .roleName("Admin")
                .status(CompanyInvitation.InvitationStatus.INVITED)
                .createdAt(OffsetDateTime.now())
                .build()

        when: "accepting invitation"
            service.acceptInvitationAndCreateAuthorizedUser(companyId, email, userId)

        then: "invitation is found"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(invitation)

        and: "transaction is executed"
            1 * dslContext.transaction(_) >> { args ->
                def transactional = args[0]
                def mockConfig = Mock(Configuration) {
                    dsl() >> dslContext
                }
                transactional.run(mockConfig)
            }

        and: "authorized user is created with invitation role"
            1 * authorizedUserRepository.save(dslContext, _ as AuthorizedUser) >> { DSLContext ctx, AuthorizedUser user ->
                assert user.companyId() == companyId
                assert user.userId() == userId
                assert user.email() == email
                assert user.roleId() == "admin"
                assert user.roleName() == "Admin"
                return user
            }

        and: "invitation is marked as accepted"
            1 * invitationRepository.save(dslContext, _ as CompanyInvitation) >> { DSLContext ctx, CompanyInvitation inv ->
                assert inv.status() == CompanyInvitation.InvitationStatus.ACCEPTED
            }
    }

    def "acceptInvitationAndCreateAuthorizedUser throws NotFoundException when no invitation"() {
        given: "no invitation exists"
            def companyId = 1L
            def email = "unknown@business.com"
            def userId = "supabase-user-789"

        when: "accepting invitation"
            service.acceptInvitationAndCreateAuthorizedUser(companyId, email, userId)

        then: "invitation lookup returns empty"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.empty()

        and: "NotFoundException is thrown"
            def ex = thrown(NotFoundException)
            ex.message.contains("No invitation found")

        and: "no transaction executed"
            0 * dslContext.transaction(_)
    }

    def "acceptInvitationAndCreateAuthorizedUser preserves role from invitation"() {
        given: "an invitation with viewer role"
            def companyId = 1L
            def email = "viewer@business.com"
            def userId = "supabase-user-viewer"
            def invitation = CompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId("viewer")
                .roleName("Viewer")
                .status(CompanyInvitation.InvitationStatus.INVITED)
                .createdAt(OffsetDateTime.now())
                .build()

        when: "accepting invitation"
            service.acceptInvitationAndCreateAuthorizedUser(companyId, email, userId)

        then: "invitation is found"
            1 * invitationRepository.findByCompanyIdAndEmail(companyId, email) >> Optional.of(invitation)

        and: "transaction mocks"
            1 * dslContext.transaction(_) >> { args ->
                args[0].run(Mock(Configuration) { dsl() >> dslContext })
            }

        and: "user gets viewer role from invitation"
            1 * authorizedUserRepository.save(_, _ as AuthorizedUser) >> { DSLContext ctx, AuthorizedUser user ->
                assert user.roleId() == "viewer"
                assert user.roleName() == "Viewer"
                return user
            }

        and: "invitation is updated"
            1 * invitationRepository.save(_, _)
    }
}
