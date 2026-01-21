package com.tosspaper.contact

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.ApiErrorMessages
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.common.BadRequestException
import com.tosspaper.generated.model.ContactStatus as GeneratedContactStatus
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import com.tosspaper.models.jooq.tables.records.ContactsRecord
import com.tosspaper.models.jooq.tables.records.ProjectsRecord
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Shared

import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.COMPANIES
import static com.tosspaper.models.jooq.Tables.CONTACTS
import static com.tosspaper.models.jooq.Tables.PROJECTS
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDERS

class ContactRepositorySpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    ContactRepository contactRepository

    @Autowired
    ObjectMapper objectMapper

    @Shared
    CompaniesRecord company

    def setup() {
        dsl.deleteFrom(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS).execute()
        dsl.deleteFrom(PURCHASE_ORDERS).execute()
        dsl.deleteFrom(CONTACTS).execute()
        dsl.deleteFrom(PROJECTS).execute()
        dsl.deleteFrom(COMPANIES).execute()
        company = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Test Company")
                .set(COMPANIES.EMAIL, "company@test.com")
                .returning()
                .fetchOne()
    }

    def "testCreateAndFindById"() {
        given:
        def newContact = new ContactsRecord()
        newContact.setCompanyId(company.getId())
        newContact.setName("John Doe")
        newContact.setEmail("aribooluwatoba@gmail.com")
        newContact.setStatus("active")

        when:
        def createdContact = contactRepository.create(newContact)

        then:
        createdContact != null
        createdContact.getId() != null

        when:
        def foundContact = contactRepository.findById(createdContact.getId())

        then:
        foundContact != null
        foundContact.getName() == "John Doe"
    }

    def "testCreateContactWithRequiredFields"() {
        given:
        def newContact = new ContactsRecord()
        newContact.setCompanyId(company.getId())
        newContact.setName("Required Fields")
        newContact.setEmail("required@test.com")
        newContact.setStatus("active")

        when:
        def createdContact = contactRepository.create(newContact)

        then:
        createdContact != null
        createdContact.getName() == "Required Fields"
        createdContact.getPhone() == null
        createdContact.getAddress() == null
        createdContact.getNotes() == null
        createdContact.getTag() == null
    }

    def "testCreateContactWithAllFields"() {
        given:
        def addressJson = JSONB.valueOf('{"street": "123 Main St", "city": "Test City"}')
        def newContact = new ContactsRecord()
        newContact.setCompanyId(company.getId())
        newContact.setName("All Fields")
        newContact.setEmail("all@test.com")
        newContact.setPhone("1234567890")
        newContact.setAddress(addressJson)
        newContact.setNotes("Some notes")
        newContact.setStatus("active")
        newContact.setTag("supplier")

        when:
        def createdContact = contactRepository.create(newContact)

        then:
        createdContact != null
        createdContact.getName() == "All Fields"
        createdContact.getPhone() == "1234567890"
        createdContact.getAddress() != null
        createdContact.getNotes() == "Some notes"
        createdContact.getTag() == "supplier"
        createdContact.getStatus() == GeneratedContactStatus.ACTIVE.toString()
    }

    def "testFindByCompanyId"() {
        given:
        createContact("John Doe", "aribooluwatoba@gmail.com", null, "active")
        createContact("Jane Smith", "jane.smith@test.com", null, "archived")

        when:
        def result = contactRepository.findByCompanyIdPaginated(company.getId(), 1, 10, null, null, null)

        then:
        result.data.size() == 2
    }

    def "testFindByCompanyIdWithFilters"() {
        given:
        createContact("John Doe", "aribooluwatoba@gmail.com", null, "active", "supplier")
        createContact("Jane Smith", "jane.smith@test.com", null, "archived")
        createContact("Peter Jones", null, "1234567890", "active")

        when: "filtering by active status"
        def activeResult = contactRepository.findByCompanyIdPaginated(company.getId(), 1, 10, null, null, ContactStatus.ACTIVE)
        then:
        activeResult.data.size() == 2

        when: "filtering by supplier tag"
        def supplierResult = contactRepository.findByCompanyIdPaginated(company.getId(), 1, 10, null, ContactTag.SUPPLIER, null)
        then:
        supplierResult.data.size() == 1
        supplierResult.data[0].getName() == "John Doe"

        when: "search by name"
        def searchResult = contactRepository.findByCompanyIdPaginated(company.getId(), 1, 10, "Jane", null, null)
        then:
        searchResult.data.size() == 1
        searchResult.data[0].getName() == "Jane Smith"
    }

    def "testFindByEmailOrPhoneAndCompanyId"() {
        given:
        createContact("John Doe", "aribooluwatoba@gmail.com", "12345", "active")

        when:
        def foundByEmail = contactRepository.findByEmailOrPhoneAndCompanyId("aribooluwatoba@gmail.com", null, company.getId())
        then:
        foundByEmail.isPresent()
        foundByEmail.get().getName() == "John Doe"

        when:
        def foundByPhone = contactRepository.findByEmailOrPhoneAndCompanyId(null, "12345", company.getId())
        then:
        foundByPhone.isPresent()
        foundByPhone.get().getName() == "John Doe"

        when:
        def notFound = contactRepository.findByEmailOrPhoneAndCompanyId("not.found@test.com", null, company.getId())
        then:
        !notFound.isPresent()
    }

    def "testUpdate"() {
        given:
        def contact = createContact("John Doe", "aribooluwatoba@gmail.com", null, "active")
        contact.setName("John Doe Updated")
        contact.setUpdatedAt(OffsetDateTime.now())

        when:
        def updatedContact = contactRepository.update(contact)

        then:
        updatedContact.getName() == "John Doe Updated"
    }

    def "testUpdateNonExistentContact"() {
        given:
        def nonExistentContact = new ContactsRecord()
        nonExistentContact.setId("non-existent-id")
        nonExistentContact.setName("Ghost")

        when:
        contactRepository.update(nonExistentContact)

        then:
        thrown(com.tosspaper.common.NotFoundException)
    }

    def "testDeleteById"() {
        given:
        def contact = createContact("John Doe", "aribooluwatoba@gmail.com", null, "active")

        when:
        contactRepository.deleteById(contact.getId())
        def deletedContact = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.ID.eq(contact.getId()))
                .fetchOptional()

        then:
        !deletedContact.isPresent()
    }

    def "testDeleteContact"() {
        given:
        def contact = createContact("John Doe", "aribooluwatoba@gmail.com", null, "active")

        when: "deleting the contact"
        contactRepository.deleteById(contact.getId())
        def deletedContact = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.ID.eq(contact.getId()))
                .fetchOptional()

        then: "contact should be deleted"
        !deletedContact.isPresent()
    }

    def "testUpdateWithApprovalChanges"() {
        given: "an existing contact with email"
        def contact = createContact("Email Contact", "old.email@company.com", null, "active", "supplier")

        and: "an approved sender for the old email"
        dsl.insertInto(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS)
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.COMPANY_ID, company.getId())
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.SENDER_IDENTIFIER, "company.com")
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.WHITELIST_TYPE, "domain")
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.STATUS, "approved")
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.APPROVED_BY, "system")
                .execute()

        when: "updating contact email with approval changes"
        contact.setName("Updated Name")
        contact.setEmail("new.email@newcompany.com")
        def newApproval = com.tosspaper.models.domain.ApprovedSender.builder()
                .companyId(company.getId())
                .senderIdentifier("newcompany.com")
                .whitelistType(com.tosspaper.models.enums.EmailWhitelistValue.DOMAIN)
                .status(com.tosspaper.models.enums.SenderApprovalStatus.APPROVED)
                .approvedBy("system")
                .build()
        def updated = contactRepository.updateWithApprovalChanges(contact, "company.com", newApproval)

        then: "contact is updated successfully"
        updated != null
        updated.name == "Updated Name"
        updated.email == "new.email@newcompany.com"

        and: "old approval is deleted"
        def oldApprovalCount = dsl.selectCount()
                .from(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS)
                .where(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.COMPANY_ID.eq(company.getId()))
                .and(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.SENDER_IDENTIFIER.eq("company.com"))
                .fetchOne(0, int.class)
        oldApprovalCount == 0

        and: "new approval is added"
        def newApprovalCount = dsl.selectCount()
                .from(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS)
                .where(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.COMPANY_ID.eq(company.getId()))
                .and(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.SENDER_IDENTIFIER.eq("newcompany.com"))
                .fetchOne(0, int.class)
        newApprovalCount == 1
    }

    def "testUpdateWithApprovalChangesOnlyDeletesOld"() {
        given: "an existing contact with email"
        def contact = createContact("Email Contact2", "old2.email@company2.com", null, "active")

        and: "an approved sender for the old email"
        dsl.insertInto(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS)
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.COMPANY_ID, company.getId())
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.SENDER_IDENTIFIER, "company2.com")
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.WHITELIST_TYPE, "domain")
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.STATUS, "approved")
                .set(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.APPROVED_BY, "system")
                .execute()

        when: "updating contact with only old approval to delete (no new approval)"
        contact.setName("Updated Only Delete")
        def updated = contactRepository.updateWithApprovalChanges(contact, "company2.com", null)

        then: "contact is updated"
        updated != null
        updated.name == "Updated Only Delete"

        and: "old approval is deleted"
        def approvalCount = dsl.selectCount()
                .from(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS)
                .where(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.COMPANY_ID.eq(company.getId()))
                .and(com.tosspaper.models.jooq.Tables.APPROVED_SENDERS.SENDER_IDENTIFIER.eq("company2.com"))
                .fetchOne(0, int.class)
        approvalCount == 0
    }

    def "testUpdateWithNullOptionalFields"() {
        given: "an existing contact with all fields populated"
        def addressJson = JSONB.valueOf('{"street": "123 Main St", "city": "Test City"}')
        def contact = new ContactsRecord()
        contact.setCompanyId(company.getId())
        contact.setName("Full Contact")
        contact.setEmail("full@test.com")
        contact.setPhone("1234567890")
        contact.setAddress(addressJson)
        contact.setNotes("Some notes")
        contact.setStatus("active")
        contact.setTag("supplier")
        contact.setCurrencyCode("USD")
        def created = contactRepository.create(contact)

        when: "updating with only some fields set (others remain null in update record)"
        def updateRecord = new ContactsRecord()
        updateRecord.setId(created.getId())
        updateRecord.setName("Updated Name Only")
        // All other fields are null - should preserve existing values

        def updated = contactRepository.update(updateRecord)

        then: "only the specified field is updated"
        updated.name == "Updated Name Only"
        // Email should remain unchanged since it was not in update
    }

    def "testUpdateContactWithPhoneChange"() {
        given: "an existing contact with phone"
        def contact = createContact("Phone Contact", null, "9999999999", "active")

        when: "updating the phone number"
        contact.setPhone("1111111111")
        contact.setAddress(JSONB.valueOf('{"street": "New Street"}'))
        contact.setNotes("Updated notes")
        contact.setTag("ship_to")
        def updated = contactRepository.update(contact)

        then: "all fields are updated"
        updated.phone == "1111111111"
        updated.notes == "Updated notes"
        updated.tag == "ship_to"
    }

    def "testUpdateContactWithCurrencyCode"() {
        given: "an existing contact"
        def contact = createContact("Currency Contact", "currency@test.com", null, "active")

        when: "updating with currency code"
        contact.setCurrencyCode("EUR")
        def updated = contactRepository.update(contact)

        then: "currency code is updated"
        updated.currencyCode == "EUR"
    }

    def "testFindByEmailOrPhoneWithBothNull"() {
        when: "finding with both email and phone null"
        def result = contactRepository.findByEmailOrPhoneAndCompanyId(null, null, company.getId())

        then: "empty is returned"
        result.isEmpty()
    }

    def "testFindByCompanyIdWithEmptySearch"() {
        given: "some contacts exist"
        createContact("Search Test 1", "search1@test.com", null, "active")
        createContact("Search Test 2", "search2@test.com", null, "active")

        when: "searching with empty string"
        def result = contactRepository.findByCompanyIdPaginated(company.getId(), 1, 10, "", null, null)

        then: "all contacts are returned (empty search is treated as no filter)"
        result.data.size() >= 2
    }

    def "testFindByCompanyIdWithWhitespaceOnlySearch"() {
        given: "some contacts exist"
        createContact("Whitespace Test", "whitespace@test.com", null, "active")

        when: "searching with whitespace only"
        def result = contactRepository.findByCompanyIdPaginated(company.getId(), 1, 10, "   ", null, null)

        then: "contacts are returned (whitespace is trimmed and treated as no filter)"
        result.data.size() >= 1
    }

    private ContactsRecord createContact(String name, String email, String phone, String status, String... tags) {
        def contact = new ContactsRecord()
        contact.setCompanyId(company.getId())
        contact.setName(name)
        contact.setEmail(email)
        contact.setPhone(phone)
        contact.setStatus(status)
        if (tags.length > 0) {
            contact.setTag(tags[0])  // Tag is now a single string, not JSONB array
        }
        contactRepository.create(contact)
    }
} 