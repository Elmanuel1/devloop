package com.tosspaper.contact

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.common.SyncStatusUpdate
import com.tosspaper.models.domain.Address
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PartyTag
import org.jooq.DSLContext
import org.jooq.JSONB
import org.spockframework.spring.EnableSharedInjection
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Shared

import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.COMPANIES
import static com.tosspaper.models.jooq.Tables.CONTACTS

@EnableSharedInjection
class ContactSyncRepositorySpec extends BaseIntegrationTest {

    @Autowired
    @Shared
    DSLContext dsl

    @Autowired
    ContactSyncRepository syncRepository

    @Autowired
    ObjectMapper objectMapper

    def setup() {
        createCompany(1L, "Test Company", "company@test.com")
    }

    def cleanup() {
        dsl.deleteFrom(CONTACTS).execute()
        dsl.deleteFrom(COMPANIES).execute()
    }

    private void createCompany(long id, String name, String email) {
        dsl.insertInto(COMPANIES)
                .set(COMPANIES.ID, id)
                .set(COMPANIES.NAME, name)
                .set(COMPANIES.EMAIL, email)
                .onConflictDoNothing()
                .execute()
    }

    def "should handle empty list on upsert"() {
        when: "upserting an empty list"
        syncRepository.upsertFromProvider(1L, [])

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "should update sync status"() {
        given: "an existing contact"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-sync-status")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Test Contact")
                .set(CONTACTS.EMAIL, "test@contact.com")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.PUSH_RETRY_COUNT, 3)
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, true)
                .set(CONTACTS.PUSH_FAILURE_REASON, "Previous error")
                .execute()

        when: "updating sync status"
        syncRepository.updateSyncStatus("contact-sync-status", "quickbooks", "qb-ext-id", "v2", OffsetDateTime.now())

        then: "the sync status is updated"
        def updated = dsl.selectFrom(CONTACTS).where(CONTACTS.ID.eq("contact-sync-status")).fetchOne()
        updated.provider == "quickbooks"
        updated.externalId == "qb-ext-id"
        updated.providerVersion == "v2"
        updated.lastSyncAt != null
        updated.pushRetryCount == 0
        updated.pushPermanentlyFailed == false
        updated.pushFailureReason == null
    }

    def "should batch update sync status"() {
        given: "existing contacts"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-batch-1")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Batch Contact 1")
                .set(CONTACTS.EMAIL, "batch1@contact.com")
                .set(CONTACTS.STATUS, "active")
                .execute()

        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-batch-2")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Batch Contact 2")
                .set(CONTACTS.EMAIL, "batch2@contact.com")
                .set(CONTACTS.STATUS, "active")
                .execute()

        and: "sync status updates"
        def updates = [
                new SyncStatusUpdate("contact-batch-1", "quickbooks", "qb-batch-1", "v1", OffsetDateTime.now()),
                new SyncStatusUpdate("contact-batch-2", "quickbooks", "qb-batch-2", "v1", OffsetDateTime.now())
        ]

        when: "batch updating sync status"
        syncRepository.batchUpdateSyncStatus(updates)

        then: "all contacts are updated"
        def c1 = dsl.selectFrom(CONTACTS).where(CONTACTS.ID.eq("contact-batch-1")).fetchOne()
        c1.externalId == "qb-batch-1"
        def c2 = dsl.selectFrom(CONTACTS).where(CONTACTS.ID.eq("contact-batch-2")).fetchOne()
        c2.externalId == "qb-batch-2"
    }

    def "should handle null or empty list in batch update"() {
        when: "batch updating with null or empty list"
        syncRepository.batchUpdateSyncStatus(null)
        syncRepository.batchUpdateSyncStatus([])

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "should find contact by id"() {
        given: "an existing contact with address"
        def addressJson = JSONB.valueOf('{"address": "123 Main St", "city": "Test City", "state_or_province": "ON", "postal_code": "M1A 1A1", "country": "Canada"}')
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-find-by-id")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Find Me")
                .set(CONTACTS.EMAIL, "findme@contact.com")
                .set(CONTACTS.ADDRESS, addressJson)
                .set(CONTACTS.TAG, "supplier")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.EXTERNAL_METADATA, JSONB.valueOf('{"key": "value"}'))
                .execute()

        when: "finding by id"
        def result = syncRepository.findById("contact-find-by-id")

        then: "the contact is found"
        result != null
        result.id == "contact-find-by-id"
        result.name == "Find Me"
        result.address != null
        result.address.city == "Test City"
        result.tag == PartyTag.SUPPLIER
        result.status == Party.PartyStatus.ACTIVE
    }

    def "should return null when contact not found by id"() {
        when: "finding non-existent contact"
        def result = syncRepository.findById("non-existent-id")

        then: "null is returned"
        result == null
    }

    def "should find contacts by ids"() {
        given: "existing contacts"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-by-ids-1")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Contact 1")
                .set(CONTACTS.EMAIL, "contact1@test.com")
                .set(CONTACTS.STATUS, "active")
                .execute()

        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-by-ids-2")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Contact 2")
                .set(CONTACTS.EMAIL, "contact2@test.com")
                .set(CONTACTS.STATUS, "active")
                .execute()

        when: "finding by ids"
        def result = syncRepository.findByIds(["contact-by-ids-1", "contact-by-ids-2"])

        then: "all contacts are found"
        result.size() == 2
    }

    def "should return empty list when ids is null or empty"() {
        expect:
        syncRepository.findByIds(null) == []
        syncRepository.findByIds([]) == []
    }

    def "should find contacts needing push"() {
        given: "a contact that needs push"
        def now = OffsetDateTime.now()
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-needs-push")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Needs Push")
                .set(CONTACTS.EMAIL, "needspush@test.com")
                .set(CONTACTS.TAG, "supplier")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.UPDATED_AT, now)
                .set(CONTACTS.PUSH_RETRY_COUNT, 0)
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, false)
                // last_sync_at is null, so this contact needs push
                .execute()

        when: "finding contacts needing push"
        def result = syncRepository.findNeedingPush(1L, 10, ["supplier"], 5)

        then: "the contact is returned"
        result.size() >= 1
        result.find { it.id == "contact-needs-push" } != null
    }

    def "should find contacts needing push without tag filter"() {
        given: "a contact that needs push"
        def now = OffsetDateTime.now()
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-needs-push-no-tag")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Needs Push No Tag")
                .set(CONTACTS.EMAIL, "needspushnotag@test.com")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.UPDATED_AT, now)
                .set(CONTACTS.PUSH_RETRY_COUNT, 0)
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        when: "finding contacts needing push with null tag filter"
        def result = syncRepository.findNeedingPush(1L, 10, null, 5)

        then: "the contact is returned"
        result.size() >= 1
        result.find { it.id == "contact-needs-push-no-tag" } != null
    }

    def "should find contacts by provider and external ids"() {
        given: "existing contacts from provider"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-prov-1")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Provider Contact 1")
                .set(CONTACTS.EMAIL, "prov1@test.com")
                .set(CONTACTS.PROVIDER, "quickbooks")
                .set(CONTACTS.EXTERNAL_ID, "qb-prov-1")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.CURRENCY_CODE, "USD")
                .execute()

        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-prov-2")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Provider Contact 2")
                .set(CONTACTS.EMAIL, "prov2@test.com")
                .set(CONTACTS.PROVIDER, "quickbooks")
                .set(CONTACTS.EXTERNAL_ID, "qb-prov-2")
                .set(CONTACTS.STATUS, "active")
                .execute()

        when: "finding by provider and external ids"
        def result = syncRepository.findByProviderAndExternalIds(1L, "quickbooks", ["qb-prov-1", "qb-prov-2"])

        then: "all contacts are found"
        result.size() == 2
    }

    def "should return empty list when external ids is null or empty"() {
        expect:
        syncRepository.findByProviderAndExternalIds(1L, "quickbooks", null) == []
        syncRepository.findByProviderAndExternalIds(1L, "quickbooks", []) == []
    }

    def "should increment retry count"() {
        given: "an existing contact"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-retry-count")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Retry Contact")
                .set(CONTACTS.EMAIL, "retry@test.com")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.PUSH_RETRY_COUNT, 2)
                .execute()

        when: "incrementing retry count"
        syncRepository.incrementRetryCount("contact-retry-count", "Network error")

        then: "the retry count is incremented"
        def updated = dsl.selectFrom(CONTACTS).where(CONTACTS.ID.eq("contact-retry-count")).fetchOne()
        updated.pushRetryCount == 3
        updated.pushFailureReason == "Network error"
        updated.pushRetryLastAttemptAt != null
    }

    def "should mark as permanently failed"() {
        given: "an existing contact"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-perm-fail")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Perm Fail Contact")
                .set(CONTACTS.EMAIL, "permfail@test.com")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        when: "marking as permanently failed"
        syncRepository.markAsPermanentlyFailed("contact-perm-fail", "Invalid data")

        then: "the contact is marked as permanently failed"
        def updated = dsl.selectFrom(CONTACTS).where(CONTACTS.ID.eq("contact-perm-fail")).fetchOne()
        updated.pushPermanentlyFailed == true
        updated.pushFailureReason == "Invalid data"
    }

    def "should reset retry tracking"() {
        given: "an existing contact with retry tracking data"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-reset-retry")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Reset Retry Contact")
                .set(CONTACTS.EMAIL, "resetretry@test.com")
                .set(CONTACTS.STATUS, "active")
                .set(CONTACTS.PUSH_RETRY_COUNT, 5)
                .set(CONTACTS.PUSH_PERMANENTLY_FAILED, true)
                .set(CONTACTS.PUSH_FAILURE_REASON, "Previous error")
                .set(CONTACTS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .execute()

        when: "resetting retry tracking"
        syncRepository.resetRetryTracking("contact-reset-retry")

        then: "the retry tracking is reset"
        def updated = dsl.selectFrom(CONTACTS).where(CONTACTS.ID.eq("contact-reset-retry")).fetchOne()
        updated.pushRetryCount == 0
        updated.pushPermanentlyFailed == false
        updated.pushFailureReason == null
        updated.pushRetryLastAttemptAt == null
    }

    def "should upsert new contact from provider"() {
        given: "a new contact from provider"
        def address = Address.builder()
                .address("123 Provider St")
                .city("Provider City")
                .stateOrProvince("ON")
                .postalCode("M1A 1A1")
                .country("Canada")
                .build()

        def contact = new Party()
        contact.setName("New Provider Contact")
        contact.setEmail("newprovider@test.com")
        contact.setPhone("+1234567890")
        contact.setAddress(address)
        contact.setNotes("Provider notes")
        contact.setProvider("quickbooks")
        contact.setExternalId("qb-new-123")
        contact.setProviderVersion("v1")
        contact.setProviderCreatedAt(OffsetDateTime.now())
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)
        contact.setTag(PartyTag.SUPPLIER)
        contact.setCurrencyCode(com.tosspaper.models.domain.Currency.USD)
        contact.setExternalMetadata(Map.of("qb_id", "12345"))

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the contact is created"
        def created = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.COMPANY_ID.eq(1L))
                .and(CONTACTS.PROVIDER.eq("quickbooks"))
                .and(CONTACTS.EXTERNAL_ID.eq("qb-new-123"))
                .fetchOne()

        created != null
        created.name == "New Provider Contact"
        created.email == "newprovider@test.com"
        created.phone == "+1234567890"
        created.provider == "quickbooks"
        created.externalId == "qb-new-123"
        created.providerVersion == "v1"
        created.status == "active"
        created.tag == "supplier"
        created.currencyCode == "USD"
        created.address != null
    }

    def "should update existing contact from provider by external id"() {
        given: "an existing contact from provider"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-update-provider")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Old Name")
                .set(CONTACTS.EMAIL, "old@test.com")
                .set(CONTACTS.PROVIDER, "quickbooks")
                .set(CONTACTS.EXTERNAL_ID, "qb-update-123")
                .set(CONTACTS.PROVIDER_VERSION, "v1")
                .set(CONTACTS.STATUS, "active")
                .execute()

        and: "an updated contact from provider"
        def contact = new Party()
        contact.setName("Updated Name")
        contact.setEmail("updated@test.com")
        contact.setPhone("+9876543210")
        contact.setProvider("quickbooks")
        contact.setExternalId("qb-update-123")
        contact.setProviderVersion("v2")
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)
        contact.setTag(PartyTag.VENDOR)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the contact is updated"
        def updated = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.ID.eq("contact-update-provider"))
                .fetchOne()

        updated.name == "Updated Name"
        updated.email == "updated@test.com"
        updated.phone == "+9876543210"
        updated.providerVersion == "v2"
        updated.tag == "vendor"
    }

    def "should update existing contact by email and tag"() {
        given: "an existing local contact (no provider)"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-update-email")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Local Contact")
                .set(CONTACTS.EMAIL, "local@test.com")
                .set(CONTACTS.TAG, "supplier")
                .set(CONTACTS.STATUS, "active")
                .execute()

        and: "a provider contact with same email and tag"
        def contact = new Party()
        contact.setName("Provider Contact")
        contact.setEmail("local@test.com")
        contact.setProvider("quickbooks")
        contact.setExternalId("qb-email-123")
        contact.setProviderVersion("v1")
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)
        contact.setTag(PartyTag.SUPPLIER)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the existing contact is updated"
        def updated = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.COMPANY_ID.eq(1L))
                .and(CONTACTS.EMAIL.eq("local@test.com"))
                .fetchOne()

        updated != null
        updated.name == "Provider Contact"
    }

    def "should update existing contact by phone and tag"() {
        given: "an existing local contact with phone"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-update-phone")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Phone Contact")
                .set(CONTACTS.PHONE, "+1111111111")
                .set(CONTACTS.TAG, "vendor")
                .set(CONTACTS.STATUS, "active")
                .execute()

        and: "a provider contact with same phone and tag"
        def contact = new Party()
        contact.setName("Provider Phone Contact")
        contact.setPhone("+1111111111")
        contact.setProvider("quickbooks")
        contact.setExternalId("qb-phone-123")
        contact.setProviderVersion("v1")
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)
        contact.setTag(PartyTag.VENDOR)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the existing contact is updated"
        def updated = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.COMPANY_ID.eq(1L))
                .and(CONTACTS.PHONE.eq("+1111111111"))
                .fetchOne()

        updated != null
        updated.name == "Provider Phone Contact"
    }

    def "should update existing contact by name"() {
        given: "an existing contact with unique name"
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, "contact-update-name")
                .set(CONTACTS.COMPANY_ID, 1L)
                .set(CONTACTS.NAME, "Unique Company Name")
                .set(CONTACTS.STATUS, "active")
                .execute()

        and: "a provider contact with same name (case-insensitive, trimmed)"
        def contact = new Party()
        contact.setName("  unique company name  ")
        contact.setEmail("unique@test.com")
        contact.setProvider("quickbooks")
        contact.setExternalId("qb-name-123")
        contact.setProviderVersion("v1")
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the existing contact is updated"
        def contacts = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.COMPANY_ID.eq(1L))
                .fetch()

        contacts.size() == 1
        contacts[0].email == "unique@test.com"
    }

    def "should handle batch upsert from provider"() {
        given: "multiple contacts from provider"
        def contacts = (1..5).collect { idx ->
            def contact = new Party()
            contact.setName("Batch Contact ${idx}")
            contact.setEmail("batch${idx}@test.com")
            contact.setProvider("quickbooks")
            contact.setExternalId("qb-batch-${idx}")
            contact.setProviderVersion("v1")
            contact.setProviderLastUpdatedAt(OffsetDateTime.now())
            contact.setStatus(Party.PartyStatus.ACTIVE)
            contact.setTag(PartyTag.SUPPLIER)
            contact
        }

        when: "batch upserting from provider"
        syncRepository.upsertFromProvider(1L, contacts)

        then: "all contacts are created"
        def created = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.COMPANY_ID.eq(1L))
                .and(CONTACTS.PROVIDER.eq("quickbooks"))
                .fetch()

        created.size() >= 5
    }

    def "should handle contact with null address"() {
        given: "a contact without address"
        def contact = new Party()
        contact.setName("No Address Contact")
        contact.setEmail("noaddress@test.com")
        contact.setProvider("quickbooks")
        contact.setExternalId("qb-noaddr-123")
        contact.setProviderVersion("v1")
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)
        contact.setAddress(null)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the contact is created without address"
        def created = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.EXTERNAL_ID.eq("qb-noaddr-123"))
                .fetchOne()

        created != null
        created.address == null
    }

    def "should handle contact with null external metadata"() {
        given: "a contact without external metadata"
        def contact = new Party()
        contact.setName("No Metadata Contact")
        contact.setEmail("nometadata@test.com")
        contact.setProvider("quickbooks")
        contact.setExternalId("qb-nometa-123")
        contact.setProviderVersion("v1")
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)
        contact.setExternalMetadata(null)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the contact is created without metadata"
        def created = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.EXTERNAL_ID.eq("qb-nometa-123"))
                .fetchOne()

        created != null
        created.externalMetadata == null
    }

    def "should normalize provider name to lowercase"() {
        given: "a contact with mixed-case provider name"
        def contact = new Party()
        contact.setName("Mixed Case Provider")
        contact.setEmail("mixedcase@test.com")
        contact.setProvider("QuickBooks")
        contact.setExternalId("qb-mixed-123")
        contact.setProviderVersion("v1")
        contact.setProviderLastUpdatedAt(OffsetDateTime.now())
        contact.setStatus(Party.PartyStatus.ACTIVE)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [contact])

        then: "the provider is stored in lowercase"
        def created = dsl.selectFrom(CONTACTS)
                .where(CONTACTS.EXTERNAL_ID.eq("qb-mixed-123"))
                .fetchOne()

        created.provider == "quickbooks"
    }
}
