package com.tosspaper.company

import com.tosspaper.generated.model.Company
import com.tosspaper.generated.model.CompanyCreate
import com.tosspaper.generated.model.CompanyInfoUpdate
import com.tosspaper.generated.model.CompanyMembership
import com.tosspaper.models.config.AppEmailProperties
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import com.tosspaper.common.NotFoundException
import com.tosspaper.generated.model.Company
import com.tosspaper.rbac.AuthorizedUserRepository
import org.jooq.Configuration
import org.jooq.DSLContext
import spock.lang.Specification

import java.time.OffsetDateTime

class CompanyServiceSpec extends Specification {

    CompanyRepository companyRepository
    CompanyMapper companyMapper
    AuthorizedUserRepository authorizedUserRepository
    DSLContext dslContext
    AppEmailProperties appEmailProperties
    CompanyServiceImpl service

    def setup() {
        companyRepository = Mock()
        companyMapper = Mock()
        authorizedUserRepository = Mock()
        dslContext = Mock()
        appEmailProperties = Mock()
        service = new CompanyServiceImpl(companyRepository, companyMapper, authorizedUserRepository, dslContext, appEmailProperties)
    }

    // ==================== getAuthorizedCompanies ====================

    def "getAuthorizedCompanies returns companies with roles for user"() {
        given: "a user with authorized companies"
            def email = "user@test.com"
            def company1 = createCompaniesRecord(1L, "Company One")
            def company2 = createCompaniesRecord(2L, "Company Two")
            def companiesWithRoles = [
                new CompanyWithRole(company1, "admin"),
                new CompanyWithRole(company2, "viewer")
            ]
            def membership1 = createMembership(1L, "Company One", CompanyMembership.RoleEnum.ADMIN)
            def membership2 = createMembership(2L, "Company Two", CompanyMembership.RoleEnum.VIEWER)

        when: "fetching authorized companies"
            def result = service.getAuthorizedCompanies(email)

        then: "repository returns companies with roles"
            1 * companyRepository.findAuthorizedCompaniesByEmail(email) >> companiesWithRoles

        and: "each company is mapped with its role"
            1 * companyMapper.toDtoWithMembership(company1, CompanyMembership.RoleEnum.ADMIN) >> membership1
            1 * companyMapper.toDtoWithMembership(company2, CompanyMembership.RoleEnum.VIEWER) >> membership2

        and: "result contains all companies with correct fields"
            with(result) {
                size() == 2
                it[0].id == 1L
                it[0].name == "Company One"
                it[0].role == CompanyMembership.RoleEnum.ADMIN
                it[1].id == 2L
                it[1].name == "Company Two"
                it[1].role == CompanyMembership.RoleEnum.VIEWER
            }
    }

    def "getAuthorizedCompanies returns empty list when user has no companies"() {
        given: "a user with no authorized companies"
            def email = "newuser@test.com"

        when: "fetching authorized companies"
            def result = service.getAuthorizedCompanies(email)

        then: "repository returns empty list"
            1 * companyRepository.findAuthorizedCompaniesByEmail(email) >> []

        and: "no mapping occurs"
            0 * companyMapper.toDtoWithMembership(_, _)

        and: "result is empty"
            result.isEmpty()
    }

    def "getAuthorizedCompanies handles single company owner"() {
        given: "a user who owns one company"
            def email = "owner@test.com"
            def company = createCompaniesRecord(1L, "My Company")
            def companiesWithRoles = [new CompanyWithRole(company, "owner")]
            def membership = createMembership(1L, "My Company", CompanyMembership.RoleEnum.OWNER)

        when: "fetching authorized companies"
            def result = service.getAuthorizedCompanies(email)

        then: "repository returns single company"
            1 * companyRepository.findAuthorizedCompaniesByEmail(email) >> companiesWithRoles
            1 * companyMapper.toDtoWithMembership(company, CompanyMembership.RoleEnum.OWNER) >> membership

        and: "result contains owner company"
            with(result) {
                size() == 1
                it[0].role == CompanyMembership.RoleEnum.OWNER
            }
    }

    // ==================== createCompany ====================
    def "createCompany creates and returns new company"() {
        given: "a company create request"
            def email = "owner@test.com"
            def userId = "user-uuid-123"
            def companyCreate = new CompanyCreate()
            companyCreate.name = "New Company"
            companyCreate.assignedEmail = "controla@useassetiq.com"

            def record = createCompaniesRecord(null, "New Company")
            def savedRecord = createCompaniesRecord(1L, "New Company")
            def company = createCompany(1L, "New Company")
            def txDsl = Mock(DSLContext)

        when: "creating company"
            def result = service.createCompany(companyCreate, email, userId)

        then: "record is created from request"
            1 * companyMapper.toRecord(companyCreate, email) >> record

        and: "transaction is executed"
            1 * dslContext.transactionResult(_) >> { args ->
                return (args[0] as org.jooq.TransactionalCallable).run(Mock(Configuration) { dsl() >> txDsl })
            }

        and: "record is saved within transaction"
            1 * companyRepository.save(txDsl, record) >> savedRecord

        and: "owner membership is created with correct userId"
            1 * authorizedUserRepository.save(txDsl, { it.userId == userId })

        and: "result is mapped"
            1 * companyMapper.toDto(savedRecord) >> company

        and: "result has correct fields"
            with(result) {
                id == 1L
                name == "New Company"
            }
    }

    def "createCompany creates company with all optional fields"() {
        given: "a fully populated company create request"
            def email = "owner@test.com"
            def userId = "user-uuid-456"
            def companyCreate = new CompanyCreate()
            companyCreate.name = "Full Company"
            companyCreate.assignedEmail = "full@useassetiq.com"
            companyCreate.description = "Full Description"

            def record = createCompaniesRecord(null, "Full Company")
            def savedRecord = createCompaniesRecord(1L, "Full Company")
            savedRecord.description = "Full Description"
            def company = createCompany(1L, "Full Company")
            def txDsl = Mock(DSLContext)

        when: "creating company"
            def result = service.createCompany(companyCreate, email, userId)

        then: "record is created and saved"
            1 * companyMapper.toRecord(companyCreate, email) >> record
            1 * dslContext.transactionResult(_) >> { args ->
                return (args[0] as org.jooq.TransactionalCallable).run(Mock(Configuration) { dsl() >> txDsl })
            }
            1 * companyRepository.save(txDsl, record) >> savedRecord
            1 * authorizedUserRepository.save(txDsl, { it.userId == userId })
            1 * companyMapper.toDto(savedRecord) >> company

        and: "result is returned"
            result.id == 1L
    }

    def "createCompany falls back to email when userId is null"() {
        given: "a company create request with null userId"
            def email = "owner@test.com"
            def userId = null
            def companyCreate = new CompanyCreate()
            companyCreate.name = "Fallback Company"
            companyCreate.assignedEmail = "fallback@useassetiq.com"

            def record = createCompaniesRecord(null, "Fallback Company")
            def savedRecord = createCompaniesRecord(1L, "Fallback Company")
            def company = createCompany(1L, "Fallback Company")
            def txDsl = Mock(DSLContext)

        when: "creating company"
            def result = service.createCompany(companyCreate, email, userId)

        then: "record is created and saved"
            1 * companyMapper.toRecord(companyCreate, email) >> record
            1 * dslContext.transactionResult(_) >> { args ->
                return (args[0] as org.jooq.TransactionalCallable).run(Mock(Configuration) { dsl() >> txDsl })
            }
            1 * companyRepository.save(txDsl, record) >> savedRecord

        and: "authorized user is created with email as userId"
            1 * authorizedUserRepository.save(txDsl, { it.userId == email })

        and: "result is mapped"
            1 * companyMapper.toDto(savedRecord) >> company

        and: "result has correct fields"
            result.id == 1L
    }

    def "createCompany falls back to email when userId is empty"() {
        given: "a company create request with empty userId"
            def email = "owner@test.com"
            def userId = ""
            def companyCreate = new CompanyCreate()
            companyCreate.name = "Empty UserId Company"
            companyCreate.assignedEmail = "empty@useassetiq.com"

            def record = createCompaniesRecord(null, "Empty UserId Company")
            def savedRecord = createCompaniesRecord(1L, "Empty UserId Company")
            def company = createCompany(1L, "Empty UserId Company")
            def txDsl = Mock(DSLContext)

        when: "creating company"
            def result = service.createCompany(companyCreate, email, userId)

        then: "record is created and saved"
            1 * companyMapper.toRecord(companyCreate, email) >> record
            1 * dslContext.transactionResult(_) >> { args ->
                return (args[0] as org.jooq.TransactionalCallable).run(Mock(Configuration) { dsl() >> txDsl })
            }
            1 * companyRepository.save(txDsl, record) >> savedRecord

        and: "authorized user is created with email as userId"
            1 * authorizedUserRepository.save(txDsl, { it.userId == email })

        and: "result is mapped"
            1 * companyMapper.toDto(savedRecord) >> company

        and: "result has correct fields"
            result.id == 1L
    }

    def "createCompany generates assignedEmail when not provided"() {
        given: "a company create request without assignedEmail"
            def email = "owner@acme.com"
            def userId = "user-uuid-789"
            def companyCreate = new CompanyCreate()
            companyCreate.name = "No Email Company"
            companyCreate.assignedEmail = null

            def withAssigned = new CompanyCreate()
            withAssigned.name = "No Email Company"
            withAssigned.assignedEmail = "acme+123456@dev-clientdocs.useassetiq.com"

            def record = createCompaniesRecord(null, "No Email Company")
            def savedRecord = createCompaniesRecord(1L, "No Email Company")
            def company = createCompany(1L, "No Email Company")
            def txDsl = Mock(DSLContext)

        when: "creating company"
            def result = service.createCompany(companyCreate, email, userId)

        then: "email domain is fetched for generation"
            1 * appEmailProperties.getAllowedDomain() >> "dev-clientdocs.useassetiq.com"

        and: "mapper is called with generated assignedEmail"
            1 * companyMapper.toRecord({ it.assignedEmail != null && it.assignedEmail.contains("@dev-clientdocs.useassetiq.com") }, email) >> record

        and: "transaction is executed"
            1 * dslContext.transactionResult(_) >> { args ->
                return (args[0] as org.jooq.TransactionalCallable).run(Mock(Configuration) { dsl() >> txDsl })
            }
            1 * companyRepository.save(txDsl, record) >> savedRecord
            1 * authorizedUserRepository.save(txDsl, _)
            1 * companyMapper.toDto(savedRecord) >> company

        and: "result is returned"
            result.id == 1L
    }

    def "createCompany generates assignedEmail when blank"() {
        given: "a company create request with blank assignedEmail"
            def email = "owner@example.org"
            def userId = "user-uuid-abc"
            def companyCreate = new CompanyCreate()
            companyCreate.name = "Blank Email Company"
            companyCreate.assignedEmail = "   "

            def record = createCompaniesRecord(null, "Blank Email Company")
            def savedRecord = createCompaniesRecord(1L, "Blank Email Company")
            def company = createCompany(1L, "Blank Email Company")
            def txDsl = Mock(DSLContext)

        when: "creating company"
            def result = service.createCompany(companyCreate, email, userId)

        then: "email domain is fetched for generation"
            1 * appEmailProperties.getAllowedDomain() >> "dev-clientdocs.useassetiq.com"

        and: "mapper is called with generated assignedEmail containing domain prefix"
            1 * companyMapper.toRecord({ it.assignedEmail != null && it.assignedEmail.contains("example+") }, email) >> record

        and: "transaction is executed"
            1 * dslContext.transactionResult(_) >> { args ->
                return (args[0] as org.jooq.TransactionalCallable).run(Mock(Configuration) { dsl() >> txDsl })
            }
            1 * companyRepository.save(txDsl, record) >> savedRecord
            1 * authorizedUserRepository.save(txDsl, _)
            1 * companyMapper.toDto(savedRecord) >> company

        and: "result is returned"
            result.id == 1L
    }

    // ==================== getCompanyById ====================

    def "getCompanyById returns company when found"() {
        given: "an existing company"
            def companyId = 1L
            def record = createCompaniesRecord(companyId, "Test Company")
            def company = createCompany(companyId, "Test Company")

        when: "fetching company by ID"
            def result = service.getCompanyById(companyId)

        then: "company is fetched"
            1 * companyRepository.findById(companyId) >> record

        and: "company is mapped"
            1 * companyMapper.toDto(record) >> company

        and: "result has correct fields"
            with(result) {
                id == companyId
                name == "Test Company"
            }
    }

    def "getCompanyById throws NotFoundException when company does not exist"() {
        given: "a non-existent company ID"
            def companyId = 999L

        when: "fetching company by ID"
            service.getCompanyById(companyId)

        then: "NotFoundException is thrown"
            1 * companyRepository.findById(companyId) >> { throw new NotFoundException("api.company.notFound", "Company not found") }
            thrown(NotFoundException)
    }

    // ==================== updateCompany ====================

    def "updateCompany updates existing company"() {
        given: "an existing company"
            def companyId = 1L
            def existingRecord = createCompaniesRecord(companyId, "Old Name")
            def companyUpdate = new CompanyInfoUpdate()
            companyUpdate.name = "New Name"

            def updatedRecord = createCompaniesRecord(companyId, "New Name")
            def company = createCompany(companyId, "New Name")

        when: "updating company"
            def result = service.updateCompany(companyId, companyUpdate)

        then: "existing record is fetched"
            1 * companyRepository.findById(companyId) >> existingRecord

        and: "record is updated from DTO"
            1 * companyMapper.updateRecordFromDto(companyUpdate, existingRecord)

        and: "record is saved"
            1 * companyRepository.update(existingRecord) >> updatedRecord

        and: "result is mapped"
            1 * companyMapper.toDto(updatedRecord) >> company

        and: "result has updated fields"
            with(result) {
                id == companyId
                name == "New Name"
            }
    }

    def "updateCompany throws NotFoundException when company does not exist"() {
        given: "a non-existent company ID"
            def companyId = 999L
            def companyUpdate = new CompanyInfoUpdate()
            companyUpdate.name = "New Name"

        when: "updating company"
            service.updateCompany(companyId, companyUpdate)

        then: "NotFoundException is thrown"
            1 * companyRepository.findById(companyId) >> { throw new NotFoundException("api.company.notFound", "Company not found") }
            thrown(NotFoundException)
    }

    def "updateCompany updates only provided fields"() {
        given: "an existing company with multiple fields"
            def companyId = 1L
            def existingRecord = createCompaniesRecord(companyId, "Company Name")
            existingRecord.description = "Existing Description"

            def companyUpdate = new CompanyInfoUpdate()
            companyUpdate.description = "New Description"

            def updatedRecord = createCompaniesRecord(companyId, "Company Name")
            updatedRecord.description = "New Description"

            def company = createCompany(companyId, "Company Name")

        when: "updating company with partial data"
            def result = service.updateCompany(companyId, companyUpdate)

        then: "existing record is fetched"
            1 * companyRepository.findById(companyId) >> existingRecord

        and: "mapper updates only provided fields"
            1 * companyMapper.updateRecordFromDto(companyUpdate, existingRecord)

        and: "record is saved"
            1 * companyRepository.update(existingRecord) >> updatedRecord

        and: "result is mapped"
            1 * companyMapper.toDto(updatedRecord) >> company

        and: "result is returned"
            result != null
    }

    def "updateCompany updates multiple fields at once"() {
        given: "an existing company"
            def companyId = 1L
            def existingRecord = createCompaniesRecord(companyId, "Old Name")
            existingRecord.description = "Old Description"

            def companyUpdate = new CompanyInfoUpdate()
            companyUpdate.name = "New Name"
            companyUpdate.description = "New Description"

            def updatedRecord = createCompaniesRecord(companyId, "New Name")
            updatedRecord.description = "New Description"

            def company = createCompany(companyId, "New Name")

        when: "updating company with multiple fields"
            def result = service.updateCompany(companyId, companyUpdate)

        then: "existing record is fetched"
            1 * companyRepository.findById(companyId) >> existingRecord

        and: "all fields are updated"
            1 * companyMapper.updateRecordFromDto(companyUpdate, existingRecord)
            1 * companyRepository.update(existingRecord) >> updatedRecord
            1 * companyMapper.toDto(updatedRecord) >> company

        and: "result is returned"
            result.id == companyId
    }

    // ==================== Helper Methods ====================

    private CompaniesRecord createCompaniesRecord(Long id, String name) {
        def record = new CompaniesRecord()
        record.id = id
        record.name = name
        record.createdAt = OffsetDateTime.now()
        return record
    }

    private static Company createCompany(Long id, String name) {
        def company = new Company()
        company.id = id
        company.name = name
        return company
    }

    private static CompanyMembership createMembership(Long id, String name, CompanyMembership.RoleEnum role) {
        def membership = new CompanyMembership()
        membership.id = id
        membership.name = name
        membership.role = role
        return membership
    }
}
