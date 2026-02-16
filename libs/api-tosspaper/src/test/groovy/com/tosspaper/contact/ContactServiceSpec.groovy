package com.tosspaper.contact

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.BadRequestException
import com.tosspaper.models.exception.ForbiddenException
import com.tosspaper.common.query.PaginatedResult
import com.tosspaper.generated.model.Contact
import com.tosspaper.generated.model.ContactCreate
import com.tosspaper.generated.model.ContactList
import com.tosspaper.generated.model.ContactUpdate
import com.tosspaper.generated.model.ContactTagEnum
import com.tosspaper.generated.model.ContactStatus as GeneratedContactStatus
import com.tosspaper.integrations.push.IntegrationPushStreamPublisher
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.ApprovedSender
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.integration.IntegrationCategory
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.jooq.tables.records.ContactsRecord
import com.tosspaper.models.service.CompanyLookupService
import com.tosspaper.models.service.EmailDomainService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthentication
import spock.lang.Specification

import java.time.OffsetDateTime

class ContactServiceSpec extends Specification {

    ContactRepository contactRepository
    ContactMapper contactMapper
    CompanyLookupService companyLookupService
    EmailDomainService emailDomainService
    IntegrationPushStreamPublisher integrationPushStreamPublisher
    IntegrationConnectionService integrationConnectionService
    ObjectMapper objectMapper
    ContactServiceImpl service

    def setup() {
        contactRepository = Mock()
        contactMapper = Mock()
        companyLookupService = Mock()
        emailDomainService = Mock()
        integrationPushStreamPublisher = Mock()
        integrationConnectionService = Mock()
        objectMapper = new ObjectMapper()
        objectMapper.findAndRegisterModules()
        service = new ContactServiceImpl(
            contactRepository,
            contactMapper,
            companyLookupService,
            emailDomainService,
            integrationPushStreamPublisher,
            integrationConnectionService,
            objectMapper
        )

        // Set up real SecurityContext instead of mocking SecurityUtils
        def attributes = [email: "test@example.com", sub: "test-sub"]
        def user = new DefaultOAuth2User(Collections.emptySet(), attributes, "sub")
        def accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-token", null, null)
        SecurityContextHolder.getContext().setAuthentication(
            new BearerTokenAuthentication(user, accessToken, Collections.emptySet()))
    }

    def cleanup() {
        SecurityContextHolder.clearContext()
    }

    // ==================== getContactsPaginated ====================

    def "getContactsPaginated returns paginated list with default values"() {
        given: "a company ID with no filters"
            def companyId = 1L
            def paginatedResult = Mock(PaginatedResult) {
                getData() >> [createRecord("contact-1", companyId), createRecord("contact-2", companyId)]
            }
            def contactList = new ContactList()
            contactList.data = [createContact("contact-1"), createContact("contact-2")]

        when: "fetching contacts without parameters"
            def result = service.getContactsPaginated(companyId, null, null, null, null, null)

        then: "repository is called with defaults (page 1, pageSize 20)"
            1 * contactRepository.findByCompanyIdPaginated(companyId, 1, 20, null, null, null) >> paginatedResult

        and: "result is mapped"
            1 * contactMapper.toContactList(paginatedResult) >> contactList

        and: "result contains contacts"
            with(result) {
                data.size() == 2
                data[0].id == "contact-1"
                data[1].id == "contact-2"
            }
    }

    def "getContactsPaginated uses provided pagination parameters"() {
        given: "specific pagination parameters"
            def companyId = 1L
            def page = 3
            def pageSize = 10
            def paginatedResult = Mock(PaginatedResult) {
                getData() >> []
            }

        when: "fetching contacts with parameters"
            service.getContactsPaginated(companyId, page, pageSize, null, null, null)

        then: "repository is called with provided values"
            1 * contactRepository.findByCompanyIdPaginated(companyId, 3, 10, null, null, null) >> paginatedResult
            1 * contactMapper.toContactList(paginatedResult) >> new ContactList()
    }

    def "getContactsPaginated passes search and tag filters"() {
        given: "filter parameters"
            def companyId = 1L
            def search = "acme"
            def tag = ContactTagEnum.SUPPLIER
            def paginatedResult = Mock(PaginatedResult) {
                getData() >> []
            }

        when: "fetching contacts with filters"
            service.getContactsPaginated(companyId, 1, 20, search, tag, null)

        then: "repository is called with filters"
            1 * contactRepository.findByCompanyIdPaginated(companyId, 1, 20, "acme", ContactTag.SUPPLIER, null) >> paginatedResult
            1 * contactMapper.toContactList(paginatedResult) >> new ContactList()
    }

    // ==================== createContact ====================

    def "createContact creates contact without auto-approval when no email"() {
        given: "a create request without email"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"

            def record = createRecord("new-id", companyId)
            record.email = null
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "record is mapped from request"
            1 * contactMapper.toRecord(companyId, createRequest) >> record

        and: "contact is created without approval"
            1 * contactRepository.create(record) >> createdRecord
            0 * contactRepository.createWithApproval(_, _)

        and: "no integration connections checked (no email)"
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is mapped"
            1 * contactMapper.toDto(createdRecord) >> contact

        and: "result has correct fields"
            with(result) {
                id == "created-id"
            }
    }

    def "createContact creates contact with auto-approval for business domain email"() {
        given: "a create request with business email"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.email = "vendor@business.com"

            def record = createRecord("new-id", companyId)
            record.email = "vendor@business.com"
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

            def companyInfo = new CompanyLookupService.CompanyBasicInfo(companyId, "inbox@clientdocs.company.com", "owner@company.com", "Test Company")

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "company info is fetched for domain comparison"
            1 * companyLookupService.getCompanyById(companyId) >> companyInfo

        and: "email domain is checked"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "record is mapped"
            1 * contactMapper.toRecord(companyId, createRequest) >> record

        and: "contact is created WITH approval (domain-based)"
            1 * contactRepository.createWithApproval(record, _ as ApprovedSender) >> { ContactsRecord r, ApprovedSender approval ->
                assert approval.senderIdentifier == "business.com"
                return createdRecord
            }

        and: "integration connections checked"
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is mapped"
            1 * contactMapper.toDto(createdRecord) >> contact

        and: "result has correct ID"
            result.id == "created-id"
    }

    def "createContact creates contact with email-based approval for personal domain"() {
        given: "a create request with personal email"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.email = "vendor@gmail.com"

            def record = createRecord("new-id", companyId)
            record.email = "vendor@gmail.com"
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

            def companyInfo = new CompanyLookupService.CompanyBasicInfo(companyId, "inbox@clientdocs.company.com", "owner@company.com", "Test Company")

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "company info is fetched"
            1 * companyLookupService.getCompanyById(companyId) >> companyInfo

        and: "domain is identified as blocked (personal)"
            1 * emailDomainService.isBlockedDomain("gmail.com") >> true

        and: "record is mapped"
            1 * contactMapper.toRecord(companyId, createRequest) >> record

        and: "contact is created with email-based approval (not domain)"
            1 * contactRepository.createWithApproval(record, _ as ApprovedSender) >> { ContactsRecord r, ApprovedSender approval ->
                assert approval.senderIdentifier == "vendor@gmail.com"
                return createdRecord
            }

        and: "integration connections checked"
            1 * integrationConnectionService.listByCompany(companyId) >> []

        and: "result is mapped"
            1 * contactMapper.toDto(createdRecord) >> contact
    }

    def "createContact validates currency when multicurrency is disabled"() {
        given: "a create request with non-default currency"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.currencyCode = "EUR"

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> false
                getDefaultCurrency() >> Currency.USD
                getProvider() >> IntegrationProvider.QUICKBOOKS
            }

        when: "creating contact"
            service.createContact(companyId, createRequest)

        then: "connection is checked for accounting"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "BadRequestException is thrown for currency mismatch"
            def ex = thrown(BadRequestException)
            ex.message.contains("EUR")
            ex.message.contains("USD")
    }

    def "createContact allows any currency when multicurrency is enabled"() {
        given: "a create request with non-default currency"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.currencyCode = "EUR"

            def record = createRecord("new-id", companyId)
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> true
            }

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "connection is checked"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "contact is created (no currency validation error)"
            1 * contactMapper.toRecord(companyId, createRequest) >> record
            1 * contactRepository.create(record) >> createdRecord
            1 * integrationConnectionService.listByCompany(companyId) >> []
            1 * contactMapper.toDto(createdRecord) >> contact

        and: "result is returned"
            result.id == "created-id"
    }

    def "createContact publishes integration event when enabled connection exists"() {
        given: "a create request"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Vendor"

            def record = createRecord("new-id", companyId)
            record.tag = "supplier"
            def createdRecord = createRecord("created-id", companyId)
            createdRecord.tag = "supplier"
            def contact = createContact("created-id")

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> IntegrationProvider.QUICKBOOKS
                getStatus() >> IntegrationConnectionStatus.ENABLED
            }

        when: "creating contact"
            service.createContact(companyId, createRequest)

        then: "record is mapped and created"
            1 * contactMapper.toRecord(companyId, createRequest) >> record
            1 * contactRepository.create(record) >> createdRecord

        and: "active connections are fetched"
            1 * integrationConnectionService.listByCompany(companyId) >> [connection]

        and: "contact is mapped to Party for event"
            1 * contactMapper.toParty(createdRecord) >> Mock(Party) {
                getCurrencyCode() >> null
            }

        and: "integration push event is published"
            1 * integrationPushStreamPublisher.publish(_)

        and: "result is mapped"
            1 * contactMapper.toDto(createdRecord) >> contact
    }

    // ==================== getContactById ====================

    def "getContactById returns contact when company matches"() {
        given: "an existing contact"
            def companyId = 1L
            def contactId = "contact-123"
            def record = createRecord(contactId, companyId)
            def contact = createContact(contactId)

        when: "fetching contact by ID"
            def result = service.getContactById(companyId, contactId)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "contact is mapped"
            1 * contactMapper.toDto(record) >> contact

        and: "result has correct fields"
            with(result) {
                id == contactId
            }
    }

    def "getContactById throws ForbiddenException when company does not match"() {
        given: "a contact from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def contactId = "contact-123"
            def record = createRecord(contactId, differentCompanyId)

        when: "fetching contact by ID"
            service.getContactById(companyId, contactId)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "ForbiddenException is thrown"
            thrown(ForbiddenException)

        and: "no mapping occurs"
            0 * contactMapper.toDto(_)
    }

    // ==================== updateContact ====================

    def "updateContact updates contact and publishes event"() {
        given: "an existing contact"
            def companyId = 1L
            def contactId = "contact-123"
            def record = createRecord(contactId, companyId)
            record.email = "old@test.com"
            def updateRequest = new ContactUpdate()
            updateRequest.name = "Updated Name"

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> IntegrationProvider.QUICKBOOKS
                getStatus() >> IntegrationConnectionStatus.ENABLED
            }

        when: "updating contact"
            service.updateContact(companyId, contactId, updateRequest)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "record is updated via mapper"
            1 * contactMapper.updateRecord(updateRequest, record)

        and: "contact is saved (no email change)"
            1 * contactRepository.update(record) >> record

        and: "integration event is published"
            1 * integrationConnectionService.listByCompany(companyId) >> [connection]
            1 * contactMapper.toParty(record) >> Mock(Party) {
                getCurrencyCode() >> null
            }
            1 * integrationPushStreamPublisher.publish(_)
    }

    def "updateContact throws ForbiddenException when company does not match"() {
        given: "a contact from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def contactId = "contact-123"
            def record = createRecord(contactId, differentCompanyId)
            def updateRequest = new ContactUpdate()

        when: "updating contact"
            service.updateContact(companyId, contactId, updateRequest)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "ForbiddenException is thrown"
            thrown(ForbiddenException)

        and: "no update occurs"
            0 * contactMapper.updateRecord(_, _)
            0 * contactRepository.update(_)
    }

    def "updateContact handles email change with approval update"() {
        given: "a contact with email change"
            def companyId = 1L
            def contactId = "contact-123"
            def record = createRecord(contactId, companyId)
            record.email = "old@business.com"

            def updateRequest = new ContactUpdate()
            updateRequest.email = "new@otherbusiness.com"

            def companyInfo = new CompanyLookupService.CompanyBasicInfo(companyId, "inbox@clientdocs.company.com", "owner@company.com", "Test Company")

        when: "updating contact with new email"
            service.updateContact(companyId, contactId, updateRequest)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "record is updated (sets new email)"
            1 * contactMapper.updateRecord(updateRequest, record) >> { ContactUpdate upd, ContactsRecord rec ->
                rec.email = upd.email
            }

        and: "old domain is checked for whitelist type"
            1 * emailDomainService.isBlockedDomain("business.com") >> false

        and: "company info fetched for new approval"
            1 * companyLookupService.getCompanyById(companyId) >> companyInfo

        and: "new domain is checked"
            1 * emailDomainService.isBlockedDomain("otherbusiness.com") >> false

        and: "contact is updated with approval changes"
            1 * contactRepository.updateWithApprovalChanges(record, "business.com", _ as ApprovedSender) >> record

        and: "integration event is published"
            1 * integrationConnectionService.listByCompany(companyId) >> []
    }

    // ==================== deleteContact ====================

    def "deleteContact deletes contact when company matches"() {
        given: "an existing contact"
            def companyId = 1L
            def contactId = "contact-123"
            def record = createRecord(contactId, companyId)

        when: "deleting contact"
            service.deleteContact(companyId, contactId)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "contact is deleted"
            1 * contactRepository.deleteById(contactId)
    }

    def "deleteContact throws ForbiddenException when company does not match"() {
        given: "a contact from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def contactId = "contact-123"
            def record = createRecord(contactId, differentCompanyId)

        when: "deleting contact"
            service.deleteContact(companyId, contactId)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "ForbiddenException is thrown"
            thrown(ForbiddenException)

        and: "no deletion occurs"
            0 * contactRepository.deleteById(_)
    }

    // ==================== Coverage Gap Tests ====================

    def "createContact skips currency validation when no accounting connection"() {
        given: "a create request with currency but no accounting connection"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.currencyCode = "EUR"

            def record = createRecord("new-id", companyId)
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "no accounting connection found"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.empty()

        and: "contact is created (no currency validation error)"
            1 * contactMapper.toRecord(companyId, createRequest) >> record
            1 * contactRepository.create(record) >> createdRecord
            1 * integrationConnectionService.listByCompany(companyId) >> []
            1 * contactMapper.toDto(createdRecord) >> contact

        and: "result is returned"
            result.id == "created-id"
    }

    def "createContact skips currency validation when connection has null default currency"() {
        given: "a create request with currency but connection has no default currency"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Contact"
            createRequest.currencyCode = "EUR"

            def record = createRecord("new-id", companyId)
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> false
                getDefaultCurrency() >> null  // No default currency set
            }

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "accounting connection found but has no default currency"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "contact is created (currency validation skipped)"
            1 * contactMapper.toRecord(companyId, createRequest) >> record
            1 * contactRepository.create(record) >> createdRecord
            1 * integrationConnectionService.listByCompany(companyId) >> []
            1 * contactMapper.toDto(createdRecord) >> contact

        and: "result is returned"
            result.id == "created-id"
    }

    def "createContact publishes JOB_LOCATION event for ship_to tagged contact"() {
        given: "a create request for ship_to contact"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "Ship To Location"

            def record = createRecord("new-id", companyId)
            record.tag = "ship_to"
            def createdRecord = createRecord("created-id", companyId)
            createdRecord.tag = "ship_to"
            def contact = createContact("created-id")

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> IntegrationProvider.QUICKBOOKS
                getStatus() >> IntegrationConnectionStatus.ENABLED
            }

        when: "creating ship_to contact"
            service.createContact(companyId, createRequest)

        then: "record is mapped and created"
            1 * contactMapper.toRecord(companyId, createRequest) >> record
            1 * contactRepository.create(record) >> createdRecord

        and: "active connections are fetched"
            1 * integrationConnectionService.listByCompany(companyId) >> [connection]

        and: "contact is mapped to Party"
            1 * contactMapper.toParty(createdRecord) >> Mock(Party) {
                getCurrencyCode() >> null
            }

        and: "integration push event is published"
            1 * integrationPushStreamPublisher.publish(_)

        and: "result is mapped"
            1 * contactMapper.toDto(createdRecord) >> contact
    }

    def "createContact handles integration push exception gracefully"() {
        given: "a create request"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Vendor"

            def record = createRecord("new-id", companyId)
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

            def connection = Mock(IntegrationConnection) {
                getId() >> 100L
                getProvider() >> IntegrationProvider.QUICKBOOKS
                getStatus() >> IntegrationConnectionStatus.ENABLED
            }

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "record is mapped and created"
            1 * contactMapper.toRecord(companyId, createRequest) >> record
            1 * contactRepository.create(record) >> createdRecord

        and: "active connections are fetched"
            1 * integrationConnectionService.listByCompany(companyId) >> [connection]

        and: "contact is mapped to Party"
            1 * contactMapper.toParty(createdRecord) >> Mock(Party) {
                getCurrencyCode() >> null
            }

        and: "integration push throws exception"
            1 * integrationPushStreamPublisher.publish(_) >> { throw new RuntimeException("Push failed") }

        and: "result is still mapped (exception handled gracefully)"
            1 * contactMapper.toDto(createdRecord) >> contact

        and: "result is returned despite push failure"
            result.id == "created-id"
    }

    def "createContact handles outer exception in publishIntegrationPushEventIfNeeded gracefully"() {
        given: "a create request"
            def companyId = 1L
            def createRequest = new ContactCreate()
            createRequest.name = "New Vendor"

            def record = createRecord("new-id", companyId)
            def createdRecord = createRecord("created-id", companyId)
            def contact = createContact("created-id")

        when: "creating contact"
            def result = service.createContact(companyId, createRequest)

        then: "record is mapped and created"
            1 * contactMapper.toRecord(companyId, createRequest) >> record
            1 * contactRepository.create(record) >> createdRecord

        and: "listByCompany throws exception"
            1 * integrationConnectionService.listByCompany(companyId) >> { throw new RuntimeException("Connection lookup failed") }

        and: "result is still mapped (exception handled gracefully)"
            1 * contactMapper.toDto(createdRecord) >> contact

        and: "result is returned despite lookup failure"
            result.id == "created-id"
    }

    def "getContactsPaginated passes status filter"() {
        given: "status filter parameter"
            def companyId = 1L
            def status = GeneratedContactStatus.ACTIVE
            def paginatedResult = Mock(PaginatedResult) {
                getData() >> []
            }

        when: "fetching contacts with status filter"
            service.getContactsPaginated(companyId, 1, 20, null, null, status)

        then: "repository is called with status filter"
            1 * contactRepository.findByCompanyIdPaginated(companyId, 1, 20, null, null, ContactStatus.ACTIVE) >> paginatedResult
            1 * contactMapper.toContactList(paginatedResult) >> new ContactList()
    }

    def "updateContact validates currency when provided in update"() {
        given: "an update request with currency"
            def companyId = 1L
            def contactId = "contact-123"
            def record = createRecord(contactId, companyId)
            record.email = "test@test.com"
            def updateRequest = new ContactUpdate()
            updateRequest.currencyCode = "EUR"

            def connection = Mock(IntegrationConnection) {
                getMulticurrencyEnabled() >> false
                getDefaultCurrency() >> Currency.USD
                getProvider() >> IntegrationProvider.QUICKBOOKS
            }

        when: "updating contact with currency"
            service.updateContact(companyId, contactId, updateRequest)

        then: "contact is fetched"
            1 * contactRepository.findById(contactId) >> record

        and: "currency is validated"
            1 * integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING) >> Optional.of(connection)

        and: "BadRequestException is thrown for currency mismatch"
            def ex = thrown(BadRequestException)
            ex.message.contains("EUR")
            ex.message.contains("USD")
    }

    // ==================== Helper Methods ====================

    private ContactsRecord createRecord(String id, Long companyId) {
        def record = new ContactsRecord()
        record.id = id
        record.companyId = companyId
        record.name = "Test Contact"
        record.tag = "supplier"
        record.createdAt = OffsetDateTime.now()
        return record
    }

    private Contact createContact(String id) {
        def contact = new Contact()
        contact.id = id
        contact.name = "Test Contact"
        return contact
    }
}
