package com.tosspaper.item

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.NotFoundException
import com.tosspaper.common.DuplicateException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.common.SyncStatusUpdate
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import com.tosspaper.models.jooq.tables.records.ItemsRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Shared

import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.AUTHORIZED_USERS
import static com.tosspaper.models.jooq.Tables.COMPANIES
import static com.tosspaper.models.jooq.Tables.ITEMS
import static com.tosspaper.models.jooq.Tables.INTEGRATION_CONNECTIONS

class ItemRepositorySpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    ItemRepository itemRepository

    @Autowired
    ObjectMapper objectMapper

    @Shared
    CompaniesRecord company

    @Shared
    String connectionId

    def setup() {
        dsl.deleteFrom(ITEMS).execute()
        dsl.deleteFrom(INTEGRATION_CONNECTIONS).execute()
        dsl.deleteFrom(AUTHORIZED_USERS).execute()
        dsl.deleteFrom(COMPANIES).execute()
        company = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Test Company")
                .set(COMPANIES.EMAIL, "company@test.com")
                .returning()
                .fetchOne()

        // Create test connection for tests that need connection_id
        def connection = dsl.insertInto(INTEGRATION_CONNECTIONS)
                .set(INTEGRATION_CONNECTIONS.COMPANY_ID, company.getId())
                .set(INTEGRATION_CONNECTIONS.PROVIDER, "QUICKBOOKS")
                .set(INTEGRATION_CONNECTIONS.STATUS, "enabled")
                .set(INTEGRATION_CONNECTIONS.REALM_ID, "test-realm-123")
                .set(INTEGRATION_CONNECTIONS.ACCESS_TOKEN, "test-access-token")
                .set(INTEGRATION_CONNECTIONS.TOKEN_EXPIRES_AT, OffsetDateTime.now().plusDays(1))
                .set(INTEGRATION_CONNECTIONS.CATEGORY, "ACCOUNTING")
                .returning()
                .fetchOne()
        connectionId = connection.getId()
    }

    // ==================== findByCompanyId ====================

    def "findByCompanyId returns all items for a company"() {
        given: "multiple items for a company"
        createItem("Item 1", "CODE-1", "Service", true)
        createItem("Item 2", "CODE-2", "Inventory", false)

        when: "fetching items by company ID"
        def result = itemRepository.findByCompanyId(company.getId())

        then: "all items are returned"
        result.size() == 2
        result*.name.containsAll(["Item 1", "Item 2"])
    }

    def "findByCompanyId returns empty list when no items exist"() {
        when: "fetching items for a company with no items"
        def result = itemRepository.findByCompanyId(company.getId())

        then: "empty list is returned"
        result.isEmpty()
    }

    def "findByCompanyId does not return items from other companies"() {
        given: "items in different companies"
        createItem("Company 1 Item", "CODE-1", "Service", true)

        and: "another company with items"
        def otherCompany = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Other Company")
                .set(COMPANIES.EMAIL, "other@test.com")
                .returning()
                .fetchOne()
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, otherCompany.getId())
                .set(ITEMS.NAME, "Other Company Item")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .execute()

        when: "fetching items for the first company"
        def result = itemRepository.findByCompanyId(company.getId())

        then: "only the first company's items are returned"
        result.size() == 1
        result[0].name == "Company 1 Item"
    }

    // ==================== findById ====================

    def "findById returns item when it exists"() {
        given: "an existing item"
        def created = createItem("Test Item", "CODE-1", "Service", true)

        when: "fetching by ID"
        def result = itemRepository.findById(created.getId())

        then: "the item is returned"
        result.id == created.getId()
        result.name == "Test Item"
        result.code == "CODE-1"
        result.type == "Service"
        result.active == true
    }

    def "findById throws NotFoundException when item does not exist"() {
        when: "fetching a non-existent item"
        itemRepository.findById("non-existent-id")

        then: "NotFoundException is thrown"
        thrown(NotFoundException)
    }

    def "findById maps all fields correctly"() {
        given: "an item with all fields populated"
        def record = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, "ext-456")
                .set(ITEMS.NAME, "Full Item")
                .set(ITEMS.CODE, "FULL-001")
                .set(ITEMS.DESCRIPTION, "Full description")
                .set(ITEMS.TYPE, "Inventory")
                .set(ITEMS.UNIT_PRICE, new BigDecimal("99.99"))
                .set(ITEMS.PURCHASE_COST, new BigDecimal("50.00"))
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.TAXABLE, true)
                .set(ITEMS.QUANTITY_ON_HAND, new BigDecimal("100"))
                .returning()
                .fetchOne()

        when: "fetching by ID"
        def result = itemRepository.findById(record.getId())

        then: "all fields are mapped correctly"
        result.id == record.getId()
        result.companyId == company.getId()
        result.connectionId == connectionId
        result.externalId == "ext-456"
        result.name == "Full Item"
        result.code == "FULL-001"
        result.description == "Full description"
        result.type == "Inventory"
        result.unitPrice == new BigDecimal("99.99")
        result.purchaseCost == new BigDecimal("50.00")
        result.active == true
        result.taxable == true
        result.quantityOnHand == new BigDecimal("100")
    }

    // ==================== findByIds ====================

    def "findByIds returns items when they exist"() {
        given: "multiple items"
        def item1 = createItem("Item 1", "CODE-1", "Service", true)
        def item2 = createItem("Item 2", "CODE-2", "Inventory", true)
        createItem("Item 3", "CODE-3", "Service", false)

        when: "fetching by IDs"
        def result = itemRepository.findByIds([item1.getId(), item2.getId()])

        then: "only the requested items are returned"
        result.size() == 2
        result*.id.containsAll([item1.getId(), item2.getId()])
    }

    def "findByIds returns empty list for null input"() {
        when: "fetching with null IDs"
        def result = itemRepository.findByIds(null)

        then: "empty list is returned"
        result.isEmpty()
    }

    def "findByIds returns empty list for empty list input"() {
        when: "fetching with empty list"
        def result = itemRepository.findByIds([])

        then: "empty list is returned"
        result.isEmpty()
    }

    // ==================== create ====================

    def "create creates a new item"() {
        given: "an item to create"
        def item = Item.builder()
                .name("New Item")
                .code("NEW-001")
                .description("New description")
                .type("Service")
                .purchaseCost(new BigDecimal("25.00"))
                .active(true)
                .build()

        when: "creating the item"
        def created = itemRepository.create(company.getId(), item)

        then: "the item is created with a generated ID"
        created.id != null
        created.name == "New Item"
        created.code == "NEW-001"
        created.description == "New description"
        created.type == "Service"
        created.purchaseCost == new BigDecimal("25.00")
        created.active == true
    }

    def "create throws DuplicateException for duplicate code in same company"() {
        given: "an existing item with a code"
        createItem("Original Item", "DUPE-CODE", "Service", true)

        and: "another item with the same code"
        def duplicate = Item.builder()
                .name("Different Name")
                .code("DUPE-CODE")
                .type("Service")
                .active(true)
                .build()

        when: "creating the duplicate item"
        itemRepository.create(company.getId(), duplicate)

        then: "DuplicateException is thrown"
        thrown(DuplicateException)
    }

    // ==================== update ====================

    def "update updates an existing item"() {
        given: "an existing item"
        def created = createItem("Original Name", "ORIG-001", "Service", true)

        and: "updated values"
        def domainItem = Item.builder()
                .id(created.getId())
                .name("Updated Name")
                .code("UPD-001")
                .description("Updated description")
                .type("Inventory")
                .purchaseCost(new BigDecimal("75.00"))
                .active(false)
                .build()

        when: "updating the item"
        def updated = itemRepository.update(domainItem)

        then: "the item is updated"
        updated.name == "Updated Name"
        updated.code == "UPD-001"
        updated.description == "Updated description"
        updated.type == "Inventory"
        updated.purchaseCost == new BigDecimal("75.00")
        updated.active == false
    }

    // ==================== upsert ====================

    def "upsert creates new items"() {
        given: "items to upsert"
        def items = [
                createDomainItem("ext-1", "Item 1", "Service", true),
                createDomainItem("ext-2", "Item 2", "Inventory", false)
        ]

        when: "upserting items"
        itemRepository.upsert(company.getId(), connectionId, items)

        then: "items are created"
        def result = dsl.selectFrom(ITEMS)
                .where(ITEMS.CONNECTION_ID.eq(connectionId))
                .fetch()
        result.size() == 2
    }

    def "upsert updates existing items on conflict"() {
        given: "an existing item"
        def externalId = "ext-1"
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, externalId)
                .set(ITEMS.NAME, "Original Name")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .execute()

        and: "an item with updated values and same external ID"
        def items = [createDomainItem(externalId, "Updated Name", "Inventory", false)]

        when: "upserting the item"
        itemRepository.upsert(company.getId(), connectionId, items)

        then: "the existing item is updated"
        def result = dsl.selectFrom(ITEMS)
                .where(ITEMS.CONNECTION_ID.eq(connectionId))
                .and(ITEMS.EXTERNAL_ID.eq(externalId))
                .fetchOne()
        result.getName() == "Updated Name"
        result.getType() == "Inventory"
        result.getActive() == false
    }

    // ==================== upsertFromProvider ====================

    def "upsertFromProvider creates new items"() {
        given: "items from provider"
        def items = [
                createDomainItem("prov-1", "Provider Item 1", "Service", true),
                createDomainItem("prov-2", "Provider Item 2", "Inventory", true)
        ]

        when: "upserting from provider"
        itemRepository.upsertFromProvider(company.getId(), connectionId, items)

        then: "items are created"
        def result = dsl.selectFrom(ITEMS)
                .where(ITEMS.CONNECTION_ID.eq(connectionId))
                .fetch()
        result.size() == 2
    }

    // ==================== findIdsByExternalIdsAndConnection ====================

    def "findIdsByExternalIdsAndConnection returns mapping of external IDs to internal IDs"() {
        given: "items with external IDs"
        def item1 = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, "ext-1")
                .set(ITEMS.NAME, "Item 1")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .returning()
                .fetchOne()
        def item2 = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, "ext-2")
                .set(ITEMS.NAME, "Item 2")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .returning()
                .fetchOne()

        when: "looking up by external IDs"
        def result = itemRepository.findIdsByExternalIdsAndConnection(connectionId, ["ext-1", "ext-2"])

        then: "mapping is returned"
        result.size() == 2
        result["ext-1"] == item1.getId()
        result["ext-2"] == item2.getId()
    }

    def "findIdsByExternalIdsAndConnection returns empty map for null connectionId"() {
        when: "looking up with null connectionId"
        def result = itemRepository.findIdsByExternalIdsAndConnection(null, ["ext-1"])

        then: "empty map is returned"
        result.isEmpty()
    }

    def "findIdsByExternalIdsAndConnection returns empty map for null externalIds"() {
        when: "looking up with null externalIds"
        def result = itemRepository.findIdsByExternalIdsAndConnection("conn-1", null)

        then: "empty map is returned"
        result.isEmpty()
    }

    def "findIdsByExternalIdsAndConnection returns empty map for empty externalIds"() {
        when: "looking up with empty externalIds"
        def result = itemRepository.findIdsByExternalIdsAndConnection("conn-1", [])

        then: "empty map is returned"
        result.isEmpty()
    }

    // ==================== updateSyncStatus ====================

    def "updateSyncStatus updates sync fields"() {
        given: "an existing item"
        def created = createItem("Sync Item", "SYNC-001", "Service", true)
        def providerLastUpdatedAt = OffsetDateTime.now()

        when: "updating sync status"
        itemRepository.updateSyncStatus(
                created.getId(),
                "quickbooks",
                "qb-ext-123",
                "v1",
                providerLastUpdatedAt
        )

        then: "sync fields are updated"
        def updated = dsl.selectFrom(ITEMS)
                .where(ITEMS.ID.eq(created.getId()))
                .fetchOne()
        updated.getProvider() == "quickbooks"
        updated.getExternalId() == "qb-ext-123"
        updated.getProviderVersion() == "v1"
        updated.getLastSyncAt() != null
        updated.getPushRetryCount() == 0
        updated.getPushPermanentlyFailed() == false
        updated.getPushFailureReason() == null
    }

    // ==================== batchUpdateSyncStatus ====================

    def "batchUpdateSyncStatus updates multiple items"() {
        given: "multiple items"
        def item1 = createItem("Batch Item 1", "BATCH-1", "Service", true)
        def item2 = createItem("Batch Item 2", "BATCH-2", "Service", true)

        and: "sync status updates"
        def updates = [
                new SyncStatusUpdate(item1.getId(), "quickbooks", "qb-1", "v1", OffsetDateTime.now()),
                new SyncStatusUpdate(item2.getId(), "quickbooks", "qb-2", "v2", OffsetDateTime.now())
        ]

        when: "batch updating sync status"
        itemRepository.batchUpdateSyncStatus(updates)

        then: "all items are updated"
        def updated1 = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(item1.getId())).fetchOne()
        def updated2 = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(item2.getId())).fetchOne()
        updated1.getExternalId() == "qb-1"
        updated2.getExternalId() == "qb-2"
    }

    def "batchUpdateSyncStatus does nothing for null updates"() {
        when: "batch updating with null"
        itemRepository.batchUpdateSyncStatus(null)

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "batchUpdateSyncStatus does nothing for empty updates"() {
        when: "batch updating with empty list"
        itemRepository.batchUpdateSyncStatus([])

        then: "no exception is thrown"
        noExceptionThrown()
    }

    // ==================== findNeedingPush ====================

    def "findNeedingPush returns items that need to be pushed"() {
        given: "items with various sync states"
        // Item needing push (last_sync_at is null)
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, "needs-push")
                .set(ITEMS.NAME, "Needs Push")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, false)
                .set(ITEMS.PUSH_RETRY_COUNT, 0)
                .execute()

        // Item already synced
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, "already-synced")
                .set(ITEMS.NAME, "Already Synced")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.LAST_SYNC_AT, OffsetDateTime.now())
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, false)
                .set(ITEMS.PUSH_RETRY_COUNT, 0)
                .execute()

        when: "finding items needing push"
        def result = itemRepository.findNeedingPush(company.getId(), connectionId, 10, 3)

        then: "only items needing push are returned"
        result.size() == 1
        result[0].name == "Needs Push"
    }

    def "findNeedingPush excludes permanently failed items"() {
        given: "a permanently failed item"
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, "perm-failed")
                .set(ITEMS.NAME, "Permanently Failed")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, true)
                .set(ITEMS.PUSH_RETRY_COUNT, 5)
                .execute()

        when: "finding items needing push"
        def result = itemRepository.findNeedingPush(company.getId(), connectionId, 10, 3)

        then: "permanently failed items are excluded"
        result.isEmpty()
    }

    def "findNeedingPush excludes items over max retries"() {
        given: "an item over max retries"
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, "over-retries")
                .set(ITEMS.NAME, "Over Retries")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, false)
                .set(ITEMS.PUSH_RETRY_COUNT, 5)
                .execute()

        when: "finding items needing push with max retries of 3"
        def result = itemRepository.findNeedingPush(company.getId(), connectionId, 10, 3)

        then: "items over max retries are excluded"
        result.isEmpty()
    }

    // ==================== clearSyncStatus ====================

    def "clearSyncStatus clears provider last updated at"() {
        given: "an item with sync status"
        def record = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.NAME, "Clear Sync")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.PROVIDER_LAST_UPDATED_AT, OffsetDateTime.now())
                .returning()
                .fetchOne()

        when: "clearing sync status"
        itemRepository.clearSyncStatus(record.getId())

        then: "provider last updated at is cleared"
        def updated = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(record.getId())).fetchOne()
        updated.getProviderLastUpdatedAt() == null
    }

    // ==================== incrementRetryCount ====================

    def "incrementRetryCount increments retry count and sets error message"() {
        given: "an item with zero retry count"
        def record = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.NAME, "Retry Item")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.PUSH_RETRY_COUNT, 0)
                .returning()
                .fetchOne()

        when: "incrementing retry count"
        itemRepository.incrementRetryCount(record.getId(), "Connection timeout")

        then: "retry count is incremented"
        def updated = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(record.getId())).fetchOne()
        updated.getPushRetryCount() == 1
        updated.getPushFailureReason() == "Connection timeout"
        updated.getPushRetryLastAttemptAt() != null
    }

    // ==================== markAsPermanentlyFailed ====================

    def "markAsPermanentlyFailed marks item as permanently failed"() {
        given: "an item"
        def record = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.NAME, "Fail Item")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, false)
                .returning()
                .fetchOne()

        when: "marking as permanently failed"
        itemRepository.markAsPermanentlyFailed(record.getId(), "Invalid data format")

        then: "item is marked as permanently failed"
        def updated = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(record.getId())).fetchOne()
        updated.getPushPermanentlyFailed() == true
        updated.getPushFailureReason() == "Invalid data format"
        updated.getPushRetryLastAttemptAt() != null
    }

    // ==================== resetRetryTracking ====================

    def "resetRetryTracking resets all retry fields"() {
        given: "an item with retry tracking data"
        def record = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.NAME, "Reset Item")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .set(ITEMS.PUSH_RETRY_COUNT, 5)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, true)
                .set(ITEMS.PUSH_FAILURE_REASON, "Previous error")
                .set(ITEMS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .returning()
                .fetchOne()

        when: "resetting retry tracking"
        itemRepository.resetRetryTracking(record.getId())

        then: "all retry fields are reset"
        def updated = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(record.getId())).fetchOne()
        updated.getPushRetryCount() == 0
        updated.getPushPermanentlyFailed() == false
        updated.getPushFailureReason() == null
        updated.getPushRetryLastAttemptAt() == null
    }

    // ==================== Helper Methods ====================

    private ItemsRecord createItem(String name, String code, String type, Boolean active) {
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.NAME, name)
                .set(ITEMS.CODE, code)
                .set(ITEMS.TYPE, type)
                .set(ITEMS.ACTIVE, active)
                .returning()
                .fetchOne()
    }

    private static Item createDomainItem(String externalId, String name, String type, Boolean active) {
        def item = Item.builder()
                .name(name)
                .type(type)
                .active(active)
                .build()
        item.setExternalId(externalId)
        return item
    }
}
