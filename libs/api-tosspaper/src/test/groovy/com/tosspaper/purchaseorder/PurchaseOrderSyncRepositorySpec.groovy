package com.tosspaper.purchaseorder

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.PurchaseOrderItem
import com.tosspaper.models.domain.PurchaseOrderStatus
import org.jooq.DSLContext
import org.jooq.JSONB
import org.spockframework.spring.EnableSharedInjection
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Shared

import java.time.LocalDate
import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.COMPANIES
import static com.tosspaper.models.jooq.Tables.CONTACTS
import static com.tosspaper.models.jooq.Tables.ITEMS
import static com.tosspaper.models.jooq.Tables.PROJECTS
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDERS
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDER_ITEMS

@EnableSharedInjection
class PurchaseOrderSyncRepositorySpec extends BaseIntegrationTest {

    @Autowired
    @Shared
    DSLContext dsl

    @Autowired
    PurchaseOrderSyncRepository syncRepository

    @Autowired
    ObjectMapper objectMapper

    def setup() {
        createCompany(1L, "Test Company", "company@test.com")
        createProject("proj-1", 1L, "PROJ", "Test Project")
    }

    def cleanup() {
        dsl.deleteFrom(PURCHASE_ORDER_ITEMS).execute()
        dsl.deleteFrom(PURCHASE_ORDERS).execute()
        dsl.deleteFrom(ITEMS).execute()
        dsl.deleteFrom(CONTACTS).execute()
        dsl.deleteFrom(PROJECTS).execute()
        dsl.deleteFrom(COMPANIES).execute()
    }

    private void createItem(String id, long companyId, String name) {
        dsl.insertInto(ITEMS)
                .set(ITEMS.ID, id)
                .set(ITEMS.COMPANY_ID, companyId)
                .set(ITEMS.NAME, name)
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .onConflictDoNothing()
                .execute()
    }

    private void createCompany(long id, String name, String email) {
        dsl.insertInto(COMPANIES)
                .set(COMPANIES.ID, id)
                .set(COMPANIES.NAME, name)
                .set(COMPANIES.EMAIL, email)
                .onConflictDoNothing()
                .execute()
    }

    private void createProject(String id, long companyId, String key, String name) {
        dsl.insertInto(PROJECTS)
                .set(PROJECTS.ID, id)
                .set(PROJECTS.COMPANY_ID, companyId)
                .set(PROJECTS.KEY, key)
                .set(PROJECTS.NAME, name)
                .set(PROJECTS.STATUS, "ACTIVE")
                .onConflictDoNothing()
                .execute()
    }

    def "should handle empty list on upsert"() {
        when: "upserting an empty list"
        syncRepository.upsertFromProvider(1L, [])

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "should delete purchase orders by provider and external ids"() {
        given: "existing purchase orders from provider"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-delete-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DELETE-001")
                .set(PURCHASE_ORDERS.PROVIDER, "quickbooks")
                .set(PURCHASE_ORDERS.EXTERNAL_ID, "qb-del-1")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-delete-2")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DELETE-002")
                .set(PURCHASE_ORDERS.PROVIDER, "quickbooks")
                .set(PURCHASE_ORDERS.EXTERNAL_ID, "qb-del-2")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        when: "deleting by provider and external ids"
        def deleted = syncRepository.deleteByProviderAndExternalIds(1L, "quickbooks", ["qb-del-1", "qb-del-2"])

        then: "the purchase orders are soft deleted"
        deleted == 2
        def po1 = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-delete-1")).fetchOne()
        po1.deletedAt != null
        def po2 = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-delete-2")).fetchOne()
        po2.deletedAt != null
    }

    def "should return 0 when deleting with null or empty external ids"() {
        expect: "0 is returned"
        syncRepository.deleteByProviderAndExternalIds(1L, "quickbooks", null) == 0
        syncRepository.deleteByProviderAndExternalIds(1L, "quickbooks", []) == 0
    }

    def "should update sync status"() {
        given: "an existing purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-sync-status")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-SYNC-STATUS")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 3)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, true)
                .set(PURCHASE_ORDERS.PUSH_FAILURE_REASON, "Previous error")
                .execute()

        when: "updating sync status"
        syncRepository.updateSyncStatus("po-sync-status", "qb-new-ext-id", "v2", OffsetDateTime.now())

        then: "the sync status is updated"
        def updated = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-sync-status")).fetchOne()
        updated.externalId == "qb-new-ext-id"
        updated.providerVersion == "v2"
        updated.lastSyncAt != null
        updated.pushRetryCount == 0
        updated.pushPermanentlyFailed == false
        updated.pushFailureReason == null
    }

    def "should find purchase order by provider and external id"() {
        given: "an existing purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-find-by-provider")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-FIND-PROVIDER")
                .set(PURCHASE_ORDERS.PROVIDER, "quickbooks")
                .set(PURCHASE_ORDERS.EXTERNAL_ID, "qb-find-123")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        when: "finding by provider and external id"
        def result = syncRepository.findByProviderAndExternalId(1L, "quickbooks", "qb-find-123")

        then: "the PO is found"
        result != null
        result.id == "po-find-by-provider"
        result.displayId == "PO-FIND-PROVIDER"
    }

    def "should return null when no PO found by provider and external id"() {
        when: "finding non-existent PO"
        def result = syncRepository.findByProviderAndExternalId(1L, "quickbooks", "non-existent")

        then: "null is returned"
        result == null
    }

    def "should find purchase orders by company id and display ids"() {
        given: "existing purchase orders"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-by-display-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DISPLAY-001")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-by-display-2")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DISPLAY-002")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        when: "finding by display ids"
        def result = syncRepository.findByCompanyIdAndDisplayIds(1L, ["PO-DISPLAY-001", "PO-DISPLAY-002"])

        then: "the POs are found"
        result.size() == 2
    }

    def "should return empty list when display ids is null or empty"() {
        expect:
        syncRepository.findByCompanyIdAndDisplayIds(1L, null) == []
        syncRepository.findByCompanyIdAndDisplayIds(1L, []) == []
    }

    def "should find purchase order by id"() {
        given: "an existing purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-find-by-id")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-FIND-BY-ID")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.NOTES, "Test notes")
                .execute()

        when: "finding by id"
        def result = syncRepository.findById("po-find-by-id")

        then: "the PO is found"
        result != null
        result.id == "po-find-by-id"
        result.displayId == "PO-FIND-BY-ID"
        result.notes == "Test notes"
    }

    def "should return null when PO not found by id"() {
        when: "finding non-existent PO"
        def result = syncRepository.findById("non-existent-id")

        then: "null is returned"
        result == null
    }

    def "should increment retry count"() {
        given: "an existing purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-retry-count")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-RETRY-COUNT")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 2)
                .execute()

        when: "incrementing retry count"
        syncRepository.incrementRetryCount("po-retry-count", "Network error")

        then: "the retry count is incremented"
        def updated = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-retry-count")).fetchOne()
        updated.pushRetryCount == 3
        updated.pushFailureReason == "Network error"
        updated.pushRetryLastAttemptAt != null
    }

    def "should mark as permanently failed"() {
        given: "an existing purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-perm-fail")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-PERM-FAIL")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        when: "marking as permanently failed"
        syncRepository.markAsPermanentlyFailed("po-perm-fail", "Invalid vendor")

        then: "the PO is marked as permanently failed"
        def updated = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-perm-fail")).fetchOne()
        updated.pushPermanentlyFailed == true
        updated.pushFailureReason == "Invalid vendor"
    }

    def "should reset retry tracking"() {
        given: "an existing purchase order with retry tracking data"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-reset-retry")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-RESET-RETRY")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 5)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, true)
                .set(PURCHASE_ORDERS.PUSH_FAILURE_REASON, "Previous error")
                .set(PURCHASE_ORDERS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .execute()

        when: "resetting retry tracking"
        syncRepository.resetRetryTracking("po-reset-retry")

        then: "the retry tracking is reset"
        def updated = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-reset-retry")).fetchOne()
        updated.pushRetryCount == 0
        updated.pushPermanentlyFailed == false
        updated.pushFailureReason == null
        updated.pushRetryLastAttemptAt == null
    }

    // ==================== upsertFromProvider tests ====================

    def "should insert new purchase order from provider"() {
        given: "a new purchase order from provider"
        def po = new PurchaseOrder()
        po.setProvider("quickbooks")
        po.setExternalId("qb-new-123")
        po.setDisplayId("PO-NEW-001")
        po.setProjectId("proj-1")
        po.setOrderDate(LocalDate.now())
        po.setDueDate(LocalDate.now().plusDays(30))
        po.setStatus(PurchaseOrderStatus.PENDING)
        po.setCurrencyCode(Currency.USD)
        po.setNotes("New PO from provider")
        po.setProviderVersion("v1")
        po.setProviderCreatedAt(OffsetDateTime.now())
        po.setProviderLastUpdatedAt(OffsetDateTime.now())

        def vendor = new Party()
        vendor.setId("vendor-1")
        vendor.setName("Vendor Corp")
        po.setVendorContact(vendor)

        def shipTo = new Party()
        shipTo.setId("shipto-1")
        shipTo.setName("Ship To Location")
        po.setShipToContact(shipTo)

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [po])

        then: "the PO is inserted"
        def inserted = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.EXTERNAL_ID.eq("qb-new-123"))
                .fetchOne()
        inserted != null
        inserted.displayId == "PO-NEW-001"
        inserted.provider == "quickbooks"
        inserted.notes == "New PO from provider"
        inserted.currencyCode == "USD"
    }

    def "should insert purchase order with line items"() {
        given: "valid items exist"
        createItem("item-1", 1L, "Widget A Item")

        and: "a purchase order with items"
        def po = new PurchaseOrder()
        po.setProvider("quickbooks")
        po.setExternalId("qb-with-items-123")
        po.setDisplayId("PO-ITEMS-001")
        po.setProjectId("proj-1")
        po.setStatus(PurchaseOrderStatus.PENDING)
        po.setCurrencyCode(Currency.USD)

        def item1 = new PurchaseOrderItem()
        item1.setName("Widget A")
        item1.setQuantity(10)
        item1.setUnitPrice(new BigDecimal("25.00"))
        item1.setItemId("item-1")

        def item2 = new PurchaseOrderItem()
        item2.setName("Widget B")
        item2.setQuantity(5)
        item2.setUnitPrice(new BigDecimal("50.00"))
        // Note: accountId doesn't have FK constraint, so no need to create account

        po.setItems([item1, item2])

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [po])

        then: "the PO and items are inserted"
        def inserted = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.EXTERNAL_ID.eq("qb-with-items-123"))
                .fetchOne()
        inserted != null

        def items = dsl.selectFrom(PURCHASE_ORDER_ITEMS)
                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(inserted.id))
                .fetch()
        items.size() == 2
        items*.name.containsAll(["Widget A", "Widget B"])
    }

    def "should update existing purchase order by provider and external id"() {
        given: "an existing purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Old Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Old Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-update-ext")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-UPDATE-EXT")
                .set(PURCHASE_ORDERS.PROVIDER, "quickbooks")
                .set(PURCHASE_ORDERS.EXTERNAL_ID, "qb-update-123")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.NOTES, "Old notes")
                .execute()

        and: "an updated PO from provider"
        def po = new PurchaseOrder()
        po.setProvider("quickbooks")
        po.setExternalId("qb-update-123")
        po.setDisplayId("PO-UPDATE-EXT")
        po.setProjectId("proj-1")
        po.setStatus(PurchaseOrderStatus.COMPLETED)
        po.setCurrencyCode(Currency.USD)
        po.setNotes("Updated notes from provider")
        po.setProviderVersion("v2")

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [po])

        then: "the PO is updated"
        def updated = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.ID.eq("po-update-ext"))
                .fetchOne()
        updated.notes == "Updated notes from provider"
        updated.status == "completed"
        updated.providerVersion == "v2"
    }

    def "should update existing purchase order by display id"() {
        given: "an existing local purchase order without external id"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-local-match")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-LOCAL-001")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        and: "a PO from provider with same display id"
        def po = new PurchaseOrder()
        po.setProvider("quickbooks")
        po.setExternalId("qb-matched-123")
        po.setDisplayId("PO-LOCAL-001")
        po.setProjectId("proj-1")
        po.setStatus(PurchaseOrderStatus.PENDING)
        po.setCurrencyCode(Currency.USD)
        po.setNotes("From provider")

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [po])

        then: "the local PO is updated with provider info"
        def updated = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.ID.eq("po-local-match"))
                .fetchOne()
        updated.provider == "quickbooks"
        updated.externalId == "qb-matched-123"
        updated.notes == "From provider"
    }

    def "should replace line items on update"() {
        given: "valid items exist"
        createItem("old-item-id", 1L, "Old Item")
        createItem("new-item-id", 1L, "New Item")

        and: "an existing purchase order with items"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-replace-items")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-REPLACE-ITEMS")
                .set(PURCHASE_ORDERS.PROVIDER, "quickbooks")
                .set(PURCHASE_ORDERS.EXTERNAL_ID, "qb-replace-items")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-replace-items")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Old Item")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 1)
                .set(PURCHASE_ORDER_ITEMS.ITEM_ID, "old-item-id")
                .execute()

        and: "an updated PO with new items"
        def po = new PurchaseOrder()
        po.setProvider("quickbooks")
        po.setExternalId("qb-replace-items")
        po.setDisplayId("PO-REPLACE-ITEMS")
        po.setProjectId("proj-1")
        po.setStatus(PurchaseOrderStatus.PENDING)
        po.setCurrencyCode(Currency.CAD)

        def newItem = new PurchaseOrderItem()
        newItem.setName("New Item")
        newItem.setQuantity(5)
        newItem.setItemId("new-item-id")
        po.setItems([newItem])

        when: "upserting from provider"
        syncRepository.upsertFromProvider(1L, [po])

        then: "old items are replaced with new items"
        def items = dsl.selectFrom(PURCHASE_ORDER_ITEMS)
                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq("po-replace-items"))
                .fetch()
        items.size() == 1
        items[0].name == "New Item"
        items[0].itemId == "new-item-id"
    }

    // ==================== findNeedingPush tests ====================

    def "should find POs needing push when updated after last sync"() {
        given: "valid items exist"
        createItem("item-123", 1L, "Valid Item")

        and: "a PO with local changes after last sync"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')
        def now = OffsetDateTime.now()

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-needs-push-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-NEEDS-PUSH-1")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.UPDATED_AT, now)
                .set(PURCHASE_ORDERS.LAST_SYNC_AT, now.minusHours(1))
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        // Add a line item with itemId to satisfy the filter
        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-needs-push-1")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Valid Item")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 1)
                .set(PURCHASE_ORDER_ITEMS.ITEM_ID, "item-123")
                .execute()

        when: "finding POs needing push"
        def result = syncRepository.findNeedingPush(1L, 10, 5)

        then: "the PO is returned"
        result.size() == 1
        result[0].id == "po-needs-push-1"
    }

    def "should find POs needing push when last sync is null"() {
        given: "valid items exist"
        createItem("item-id", 1L, "Item")

        and: "a PO that has never been synced"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-never-synced")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-NEVER-SYNCED")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        // Add valid line item
        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-never-synced")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Item")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 1)
                .set(PURCHASE_ORDER_ITEMS.ITEM_ID, "item-id")
                .execute()

        when: "finding POs needing push"
        def result = syncRepository.findNeedingPush(1L, 10, 5)

        then: "the PO is returned"
        result.any { it.id == "po-never-synced" }
    }

    def "should exclude permanently failed POs from needing push"() {
        given: "a permanently failed PO"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-perm-failed")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-PERM-FAILED")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, true)
                .execute()

        when: "finding POs needing push"
        def result = syncRepository.findNeedingPush(1L, 10, 5)

        then: "the permanently failed PO is not returned"
        result.every { it.id != "po-perm-failed" }
    }

    def "should exclude POs exceeding max retries from needing push"() {
        given: "a PO that exceeded max retries"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-max-retries")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-MAX-RETRIES")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 5)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        when: "finding POs needing push with maxRetries=5"
        def result = syncRepository.findNeedingPush(1L, 10, 5)

        then: "the PO exceeding max retries is not returned"
        result.every { it.id != "po-max-retries" }
    }

    def "should exclude POs with line items missing both itemId and accountId"() {
        given: "a PO with invalid line items"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-invalid-items")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-INVALID-ITEMS")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        // Add line item with neither itemId nor accountId
        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-invalid-items")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Invalid Item")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 1)
                .execute()

        when: "finding POs needing push"
        def result = syncRepository.findNeedingPush(1L, 10, 5)

        then: "the PO with invalid items is not returned"
        result.every { it.id != "po-invalid-items" }
    }

    def "should exclude deleted POs from needing push"() {
        given: "a deleted PO"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-deleted")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DELETED")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .set(PURCHASE_ORDERS.DELETED_AT, OffsetDateTime.now())
                .execute()

        when: "finding POs needing push"
        def result = syncRepository.findNeedingPush(1L, 10, 5)

        then: "deleted PO is not returned"
        result.every { it.id != "po-deleted" }
    }

    def "should return empty list when no POs need push"() {
        when: "finding POs needing push for company with no POs"
        def result = syncRepository.findNeedingPush(999L, 10, 5)

        then: "empty list is returned"
        result.isEmpty()
    }

    def "should respect limit on findNeedingPush"() {
        given: "valid items exist"
        (1..5).each { i -> createItem("item-$i", 1L, "Item $i") }

        and: "multiple POs needing push"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        (1..5).each { i ->
            def poId = "po-limit-test-$i"
            dsl.insertInto(PURCHASE_ORDERS)
                    .set(PURCHASE_ORDERS.ID, poId)
                    .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                    .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                    .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-LIMIT-$i")
                    .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                    .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                    .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                    .set(PURCHASE_ORDERS.STATUS, "pending")
                    .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                    .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                    .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                    .execute()

            // Add valid line item
            dsl.insertInto(PURCHASE_ORDER_ITEMS)
                    .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, poId)
                    .set(PURCHASE_ORDER_ITEMS.NAME, "Item $i")
                    .set(PURCHASE_ORDER_ITEMS.QUANTITY, 1)
                    .set(PURCHASE_ORDER_ITEMS.ITEM_ID, "item-$i")
                    .execute()
        }

        when: "finding POs needing push with limit 2"
        def result = syncRepository.findNeedingPush(1L, 2, 5)

        then: "only 2 POs are returned"
        result.size() == 2
    }

    def "should load line items for POs needing push"() {
        given: "valid items exist"
        createItem("item-widget-a", 1L, "Widget A Item")

        and: "a PO with line items"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-with-items-push")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-WITH-ITEMS-PUSH")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .execute()

        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-with-items-push")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Widget A")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 10)
                .set(PURCHASE_ORDER_ITEMS.UNIT_PRICE, new BigDecimal("25.00"))
                .set(PURCHASE_ORDER_ITEMS.ITEM_ID, "item-widget-a")
                .execute()

        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-with-items-push")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Widget B")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 5)
                .set(PURCHASE_ORDER_ITEMS.UNIT_PRICE, new BigDecimal("50.00"))
                .set(PURCHASE_ORDER_ITEMS.ITEM_ID, "item-widget-a")  // Use same valid item
                .execute()

        when: "finding POs needing push"
        def result = syncRepository.findNeedingPush(1L, 10, 5)

        then: "PO includes line items"
        def po = result.find { it.id == "po-with-items-push" }
        po != null
        po.items.size() == 2
        po.items*.name.containsAll(["Widget A", "Widget B"])
    }

    // ==================== findById with line items test ====================

    def "should find purchase order by id with line items"() {
        given: "valid items exist"
        createItem("item-1", 1L, "Item 1")

        and: "a purchase order with line items"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-by-id-items")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-BY-ID-ITEMS")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 2)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .set(PURCHASE_ORDERS.PUSH_FAILURE_REASON, "Temp error")
                .execute()

        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-by-id-items")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Line Item 1")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 3)
                .set(PURCHASE_ORDER_ITEMS.UNIT_PRICE, new BigDecimal("100.00"))
                .set(PURCHASE_ORDER_ITEMS.ITEM_ID, "item-1")
                .execute()

        when: "finding by id"
        def result = syncRepository.findById("po-by-id-items")

        then: "the PO includes line items and retry tracking"
        result != null
        result.items.size() == 1
        result.items[0].name == "Line Item 1"
        result.pushRetryCount == 2
        result.pushPermanentlyFailed == false
        result.pushFailureReason == "Temp error"
    }

    // ==================== findByProviderAndExternalId with line items test ====================

    def "should find purchase order by provider and external id with line items"() {
        given: "a purchase order with line items"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-provider-items")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-PROVIDER-ITEMS")
                .set(PURCHASE_ORDERS.PROVIDER, "xero")
                .set(PURCHASE_ORDERS.EXTERNAL_ID, "xero-ext-123")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-provider-items")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Provider Item")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 7)
                .set(PURCHASE_ORDER_ITEMS.EXTERNAL_ITEM_ID, "ext-item-1")
                .execute()

        when: "finding by provider and external id"
        def result = syncRepository.findByProviderAndExternalId(1L, "xero", "xero-ext-123")

        then: "the PO includes line items"
        result != null
        result.items.size() == 1
        result.items[0].name == "Provider Item"
        result.items[0].externalItemId == "ext-item-1"
    }

    // ==================== findByCompanyIdAndDisplayIds with line items test ====================

    def "should find purchase orders by display ids with line items"() {
        given: "purchase orders with line items"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor Corp"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-display-items-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DISP-ITEMS-001")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .execute()

        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, "po-display-items-1")
                .set(PURCHASE_ORDER_ITEMS.NAME, "Display Item 1")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 2)
                .execute()

        when: "finding by display ids"
        def result = syncRepository.findByCompanyIdAndDisplayIds(1L, ["PO-DISP-ITEMS-001"])

        then: "the POs include line items"
        result.size() == 1
        result[0].items.size() == 1
        result[0].items[0].name == "Display Item 1"
    }

    def "should not include deleted POs in findByProviderAndExternalId"() {
        given: "a deleted purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-deleted-find")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DELETED-FIND")
                .set(PURCHASE_ORDERS.PROVIDER, "quickbooks")
                .set(PURCHASE_ORDERS.EXTERNAL_ID, "qb-deleted-123")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.DELETED_AT, OffsetDateTime.now())
                .execute()

        when: "finding by provider and external id"
        def result = syncRepository.findByProviderAndExternalId(1L, "quickbooks", "qb-deleted-123")

        then: "null is returned for deleted PO"
        result == null
    }

    def "should not include deleted POs in findByCompanyIdAndDisplayIds"() {
        given: "a deleted purchase order"
        def vendorContactJson = JSONB.valueOf('{"id": "vendor-1", "name": "Vendor"}')
        def shipToContactJson = JSONB.valueOf('{"id": "shipto-1", "name": "Ship To"}')

        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-deleted-display")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-DELETED-DISPLAY")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "pending")
                .set(PURCHASE_ORDERS.DELETED_AT, OffsetDateTime.now())
                .execute()

        when: "finding by display ids"
        def result = syncRepository.findByCompanyIdAndDisplayIds(1L, ["PO-DELETED-DISPLAY"])

        then: "deleted PO is not returned"
        result.isEmpty()
    }
}
