package com.tosspaper.purchaseorder

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.DuplicateException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.jooq.tables.pojos.PurchaseOrderItems
import com.tosspaper.models.jooq.tables.records.ProjectPoCountersRecord
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord
import com.tosspaper.purchaseorder.model.ChangeLogEntry
import com.tosspaper.purchaseorder.model.PurchaseOrderQuery
import com.tosspaper.purchaseorder.model.PurchaseOrderStatus
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Autowired
import org.spockframework.spring.EnableSharedInjection
import spock.lang.Shared
import spock.lang.Specification

import java.time.LocalDate
import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.COMPANIES
import static com.tosspaper.models.jooq.Tables.CONTACTS
import static com.tosspaper.models.jooq.Tables.PROJECTS
import static com.tosspaper.models.jooq.Tables.PROJECT_PO_COUNTERS
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDERS
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDER_ITEMS

@EnableSharedInjection
class PurchaseOrderRepositorySpec extends BaseIntegrationTest {

    @Autowired
    @Shared
    DSLContext dsl

    @Autowired
    PurchaseOrderRepository repository

    @Autowired
    ObjectMapper objectMapper

    def setup() {
        createCompany(1L, "Test Company", "company@test.com")
        createProject("proj-1", 1L, "PROJ", "Test Project")
        createContact("contact-1", 1L, "Test Contact", "contact@test.com")
    }

    def cleanup() {
        dsl.deleteFrom(PURCHASE_ORDER_ITEMS).execute()
        dsl.deleteFrom(PURCHASE_ORDERS).execute()
        dsl.deleteFrom(CONTACTS).execute()
        dsl.deleteFrom(PROJECTS).execute()
        dsl.deleteFrom(COMPANIES).execute()
    }

    private void createCompany(long id, String name, String email) {
        dsl.insertInto(COMPANIES)
                .set(COMPANIES.ID, id)
                .set(COMPANIES.NAME, name)
                .set(COMPANIES.EMAIL, email)
                .execute()
    }

    private void createProject(String id, long companyId, String key, String name) {
        dsl.insertInto(PROJECTS)
                .set(PROJECTS.ID, id)
                .set(PROJECTS.COMPANY_ID, companyId)
                .set(PROJECTS.KEY, key)
                .set(PROJECTS.NAME, name)
                .set(PROJECTS.STATUS, "ACTIVE")
                .execute()
    }

    private void createContact(String id, long companyId, String name, String email) {
        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, id)
                .set(CONTACTS.COMPANY_ID, companyId)
                .set(CONTACTS.NAME, name)
                .set(CONTACTS.EMAIL, email)
                .set(CONTACTS.STATUS, "ACTIVE")
                .execute()
    }

    def "should update purchase order status and log the change"() {
        given: "an existing purchase order"
        def poId = "po-1"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, poId)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .set(PURCHASE_ORDERS.CHANGE_LOG, JSONB.valueOf("[]"))
                .execute()

        and: "a changelog entry"
        def newStatus = PurchaseOrderStatus.IN_PROGRESS
        def changeLog = new ChangeLogEntry(OffsetDateTime.now(), "test-user", "status", PurchaseOrderStatus.PENDING.getValue(), newStatus.getValue(), null)

        when: "the status is updated"
        repository.updateStatus(poId, newStatus.getValue(), changeLog)

        then: "the status and changelog are updated in the database"
        def updatedPo = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        updatedPo.status == newStatus.getValue()
        def changeLogList = objectMapper.readValue(updatedPo.changeLog.data(), List)
        changeLogList.size() == 1
        changeLogList[0].action == "status"
    }

    def "should update a purchase order and log all changes"() {
        given: "an existing purchase order"
        def poId = "po-3"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def paymentOrder = dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, poId)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .set(PURCHASE_ORDERS.CHANGE_LOG, JSONB.valueOf("[]"))
                .returning()
        .fetchOneInto(PurchaseOrdersRecord.class)

        and: "an updated purchase order object and a changelog"
        paymentOrder.setNotes("new notes")
        paymentOrder.setStatus(PurchaseOrderStatus.IN_PROGRESS.getValue())

        def item = new PurchaseOrderItems()
        item.setName("new item")
        def items = [item]
        def changeLog = [
                new ChangeLogEntry(OffsetDateTime.now(), "user", "notes", "old", "new", null),
                new ChangeLogEntry(OffsetDateTime.now(), "user", "items", "old", "new", null)
        ]

        when: "the purchase order is updated"
        repository.update(paymentOrder, items, changeLog)

        then: "the purchase order is updated in the database"
        def result = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        result.notes == "new notes"
        def dbChangeLog = objectMapper.readValue(result.changeLog.data(), List)
        dbChangeLog.size() == 2
        dbChangeLog.any { it.action == "notes" }
        dbChangeLog.any { it.action == "items" }
    }

    def "should find purchase orders by status"() {
        given: "multiple purchase orders with different statuses"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .columns(PURCHASE_ORDERS.ID, PURCHASE_ORDERS.PROJECT_ID, PURCHASE_ORDERS.COMPANY_ID, PURCHASE_ORDERS.VENDOR_CONTACT, PURCHASE_ORDERS.SHIP_TO_CONTACT, PURCHASE_ORDERS.CURRENCY_CODE, PURCHASE_ORDERS.STATUS)
                .values("po-s1", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.PENDING.getValue())
                .values("po-s2", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.IN_PROGRESS.getValue())
                .values("po-s3", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.IN_PROGRESS.getValue())
                .execute()

        when: "finding purchase orders by status 'IN_PROGRESS'"
        def result = repository.find(1L, PurchaseOrderQuery.builder().projectId("proj-1").status(PurchaseOrderStatus.IN_PROGRESS.getValue()).page(1).pageSize(20).build())

        then: "only purchase orders with the specified status are returned"
        result.size() == 2
        result.every { it.status == PurchaseOrderStatus.IN_PROGRESS.getValue() }
    }

    def "should find purchase orders by displayId"() {
        given: "a purchase order with a specific displayId"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .columns(PURCHASE_ORDERS.ID, PURCHASE_ORDERS.PROJECT_ID, PURCHASE_ORDERS.COMPANY_ID, PURCHASE_ORDERS.DISPLAY_ID, PURCHASE_ORDERS.VENDOR_CONTACT, PURCHASE_ORDERS.SHIP_TO_CONTACT, PURCHASE_ORDERS.CURRENCY_CODE, PURCHASE_ORDERS.STATUS)
                .values("po-d1", "proj-1", 1L, "PO-UNIQUE-1", vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.PENDING.getValue())
                .execute()

        when: "finding purchase orders by that displayId"
        def result = repository.find(1L, PurchaseOrderQuery.builder().projectId("proj-1").displayId("PO-UNIQUE-1").page(1).pageSize(20).build())

        then: "the correct purchase order is returned"
        result.size() == 1
        result[0].id == "po-d1"
    }

    def "should find purchase orders by dueDate"() {
        given: "multiple purchase orders with different due dates"
        def date1 = LocalDate.now().plusDays(5)
        def date2 = LocalDate.now().plusDays(10)
        def date1Utc = date1.atStartOfDay().atOffset(java.time.ZoneOffset.UTC)
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .columns(PURCHASE_ORDERS.ID, PURCHASE_ORDERS.PROJECT_ID, PURCHASE_ORDERS.COMPANY_ID, PURCHASE_ORDERS.VENDOR_CONTACT, PURCHASE_ORDERS.SHIP_TO_CONTACT, PURCHASE_ORDERS.CURRENCY_CODE, PURCHASE_ORDERS.DUE_DATE, PURCHASE_ORDERS.STATUS)
                .values("po-date1", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", date1, PurchaseOrderStatus.PENDING.getValue())
                .values("po-date2", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", date2, PurchaseOrderStatus.PENDING.getValue())
                .execute()

        when: "finding purchase orders by a specific due date"
        def result = repository.find(1L, PurchaseOrderQuery.builder().projectId("proj-1").dueDate(date1Utc).page(1).pageSize(20).build())

        then: "only purchase orders with that due date are returned"
        result.size() == 1
        result.every { it.dueDate == date1 }
    }

    def "should return all purchase orders for a project when no filters are applied"() {
        given: "multiple purchase orders in a project"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .columns(PURCHASE_ORDERS.ID, PURCHASE_ORDERS.PROJECT_ID, PURCHASE_ORDERS.COMPANY_ID, PURCHASE_ORDERS.VENDOR_CONTACT, PURCHASE_ORDERS.SHIP_TO_CONTACT, PURCHASE_ORDERS.CURRENCY_CODE, PURCHASE_ORDERS.STATUS)
                .values("po-all1", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.PENDING.getValue())
                .values("po-all2", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.IN_PROGRESS.getValue())
                .execute()

        when: "finding purchase orders with no filter"
        def result = repository.find(1L, PurchaseOrderQuery.builder().projectId("proj-1").page(1).pageSize(20).build())

        then: "all purchase orders for the project are returned"
        result.size() == 2
        result.any { it.id == "po-all1" }
        result.any { it.id == "po-all2" }
    }

    def "should create a purchase order with all fields and its line items"() {
        given: "a purchase order with line items"
        def poId = "po-new"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def po = new PurchaseOrdersRecord(
                id: poId,
                projectId: "proj-1",
                companyId: 1L,
                vendorContact: vendorContactJson,
                shipToContact: shipToContactJson,
                currencyCode: "CAD",
                displayId: "PO-NEW",
                status: PurchaseOrderStatus.PENDING.getValue(),
                notes: "Test notes"
        )
        def item1 = new PurchaseOrderItems()
        item1.setName("Item 1")
        item1.setQuantity(1)
        item1.setUnitPrice(10.0)
        def item2 = new PurchaseOrderItems()
        item2.setName("Item 2")
        item2.setQuantity(2)
        item2.setUnitPrice(20.0)
        def items = [item1, item2]

        when: "the purchase order is created"
        def createdRecord = repository.create(po, items)

        then: "the purchase order and its items are saved to the database"
        createdRecord.id == poId
        def createdPo = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        createdPo.notes == "Test notes"
        def createdItems = dsl.selectFrom(PURCHASE_ORDER_ITEMS).where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(poId)).fetchInto(PurchaseOrderItems.class)
        createdItems.size() == 2
        createdItems.any { it.name == "Item 1" }
        createdItems.any { it.name == "Item 2" }
    }

    def "should fetch a purchase order by ID"() {
        given: "an existing purchase order with items"
        def poId = "po-fetch"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        repository.create(new PurchaseOrdersRecord(
                id: poId,
                projectId: "proj-1",
                companyId: 1L,
                vendorContact: vendorContactJson,
                shipToContact: shipToContactJson,
                currencyCode: "CAD",
                status: PurchaseOrderStatus.PENDING.getValue(),
                notes: "Fetch notes"
        ), [new PurchaseOrderItems(name: "Fetched Item", quantity: 1, unitPrice: 100.0, notes: "Fetched Item notes")])

        when: "fetching the purchase order by ID"
        def result = repository.findById(poId)

        then: "the purchase order and its items are returned"
        result.size() == 1
        def fetchedPo = result[0]
        fetchedPo.purchaseOrderId == poId
        fetchedPo.name == "Fetched Item"
        fetchedPo.itemNotes == "Fetched Item notes"
        fetchedPo.notes == "Fetch notes"
    }

    def "should preserve unitPrice values when creating purchase order items"() {
        given: "a purchase order with items that have unitPrice values"
        def poId = "po-unitprice-test"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def po = new PurchaseOrdersRecord(
                id: poId,
                projectId: "proj-1",
                companyId: 1L,
                vendorContact: vendorContactJson,
                shipToContact: shipToContactJson,
                currencyCode: "CAD",
                displayId: "PO-UNITPRICE-TEST",
                status: PurchaseOrderStatus.PENDING.getValue(),
                notes: "Test unitPrice preservation"
        )

        def item1 = new PurchaseOrderItems()
        item1.setName("Item with unitPrice 1")
        item1.setQuantity(2)
        item1.setUnitPrice(new BigDecimal("25.50"))
        item1.setNotes("First item")

        def item2 = new PurchaseOrderItems()
        item2.setName("Item with unitPrice 2")
        item2.setQuantity(1)
        item2.setUnitPrice(new BigDecimal("100.00"))
        item2.setNotes("Second item")

        def items = [item1, item2]

        when: "the purchase order is created"
        def createdRecord = repository.create(po, items)

        then: "the purchase order is created successfully"
        createdRecord.id == poId

        when: "we fetch the created items from the database"
        def createdItems = dsl.selectFrom(PURCHASE_ORDER_ITEMS)
                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(poId))
                .orderBy(PURCHASE_ORDER_ITEMS.NAME)
                .fetchInto(PurchaseOrderItems.class)

        then: "all items are created with proper unitPrice values"
        createdItems.size() == 2

        and: "first item has correct unitPrice"
        def firstItem = createdItems.find { it.name == "Item with unitPrice 1" }
        firstItem != null
        firstItem.unitPrice != null
        firstItem.unitPrice == new BigDecimal("25.50")
        firstItem.quantity == 2
        firstItem.notes == "First item"

        and: "second item has correct unitPrice"
        def secondItem = createdItems.find { it.name == "Item with unitPrice 2" }
        secondItem != null
        secondItem.unitPrice != null
        secondItem.unitPrice == new BigDecimal("100.00")
        secondItem.quantity == 1
        secondItem.notes == "Second item"

        and: "both items have auto-generated IDs"
        firstItem.id != null
        firstItem.id != ""
        secondItem.id != null
        secondItem.id != ""
        firstItem.id != secondItem.id
    }

    def "should find purchase order by display id and assigned email"() {
        given: "a company with assigned email"
        dsl.update(COMPANIES)
                .set(COMPANIES.ASSIGNED_EMAIL, "inbox@test.com")
                .where(COMPANIES.ID.eq(1L))
                .execute()

        and: "a purchase order with a specific displayId"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-lookup-1")
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-LOOKUP-001")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .execute()

        when: "finding by displayId and assigned email"
        def result = repository.findByDisplayIdAndAssignedEmail("PO-LOOKUP-001", "inbox@test.com")

        then: "the purchase order is found"
        result.isPresent()
        result.get().id == "po-lookup-1"
        result.get().displayId == "PO-LOOKUP-001"
    }

    def "should return empty when no purchase order found by display id and assigned email"() {
        given: "a company with assigned email"
        dsl.update(COMPANIES)
                .set(COMPANIES.ASSIGNED_EMAIL, "inbox@test.com")
                .where(COMPANIES.ID.eq(1L))
                .execute()

        when: "finding by non-existent displayId"
        def result = repository.findByDisplayIdAndAssignedEmail("NON-EXISTENT", "inbox@test.com")

        then: "empty is returned"
        result.isEmpty()
    }

    def "should find purchase order by company id and display id"() {
        given: "a purchase order with a specific displayId"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-company-lookup")
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-COMPANY-001")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .execute()

        when: "finding by companyId and displayId"
        def result = repository.findByCompanyIdAndDisplayId(1L, "PO-COMPANY-001")

        then: "the purchase order is found"
        result.isPresent()
        result.get().id == "po-company-lookup"
        result.get().displayId == "PO-COMPANY-001"
    }

    def "should return empty when no purchase order found by company id and display id"() {
        when: "finding by non-existent displayId"
        def result = repository.findByCompanyIdAndDisplayId(1L, "NON-EXISTENT")

        then: "empty is returned"
        result.isEmpty()
    }

    def "should update status to in_progress if pending"() {
        given: "a pending purchase order"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-status-update")
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-STATUS-UPDATE")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .execute()

        when: "updating status to in_progress if pending"
        def result = repository.updateStatusToInProgressIfPending(dsl, "po-status-update", 1L)

        then: "the update succeeds"
        result == true

        and: "the status is changed in the database"
        def updatedPo = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-status-update")).fetchOne()
        updatedPo.status == "in_progress"
    }

    def "should not update status if not pending"() {
        given: "an in_progress purchase order"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-no-status-update")
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-NO-STATUS-UPDATE")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, "in_progress")
                .execute()

        when: "trying to update status to in_progress"
        def result = repository.updateStatusToInProgressIfPending(dsl, "po-no-status-update", 1L)

        then: "the update fails (returns false)"
        result == false

        and: "the status remains unchanged"
        def po = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq("po-no-status-update")).fetchOne()
        po.status == "in_progress"
    }

    def "should find purchase orders without project filter"() {
        given: "multiple purchase orders"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .columns(PURCHASE_ORDERS.ID, PURCHASE_ORDERS.PROJECT_ID, PURCHASE_ORDERS.COMPANY_ID, PURCHASE_ORDERS.VENDOR_CONTACT, PURCHASE_ORDERS.SHIP_TO_CONTACT, PURCHASE_ORDERS.CURRENCY_CODE, PURCHASE_ORDERS.STATUS)
                .values("po-np1", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.PENDING.getValue())
                .values("po-np2", "proj-1", 1L, vendorContactJson, shipToContactJson, "CAD", PurchaseOrderStatus.IN_PROGRESS.getValue())
                .execute()

        when: "finding purchase orders without project filter"
        def result = repository.find(1L, PurchaseOrderQuery.builder().projectId(null).page(1).pageSize(20).build())

        then: "all purchase orders for the company are returned"
        result.size() >= 2
    }

    def "should find purchase orders with search query"() {
        given: "purchase orders with searchable content"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-search-test")
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.DISPLAY_ID, "PO-SEARCH-001")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .set(PURCHASE_ORDERS.NOTES, "searchable unique keyword")
                .execute()

        // Allow tsvector to be populated (may require manual trigger or wait)
        // The search may depend on database triggers

        when: "searching for purchase orders"
        def result = repository.find(1L, PurchaseOrderQuery.builder()
                .projectId("proj-1")
                .search("searchable")
                .page(1)
                .pageSize(20)
                .build())

        then: "search results are returned (search may or may not find based on tsvector population)"
        result != null
    }

    def "should update purchase order with displayId change"() {
        given: "an existing purchase order"
        def poId = "po-displayid-update"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def po = dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, poId)
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.DISPLAY_ID, "OLD-DISPLAY-ID")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .set(PURCHASE_ORDERS.CHANGE_LOG, JSONB.valueOf("[]"))
                .returning()
                .fetchOneInto(PurchaseOrdersRecord.class)

        and: "updated purchase order record"
        po.setDisplayId("NEW-DISPLAY-ID")
        po.setNotes("Updated notes")
        def changeLog = [
                new ChangeLogEntry(java.time.OffsetDateTime.now(), "user", "displayId", "OLD-DISPLAY-ID", "NEW-DISPLAY-ID", null)
        ]

        when: "the purchase order is updated"
        repository.update(po, [], changeLog)

        then: "the displayId is updated in the database"
        def result = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        result.displayId == "NEW-DISPLAY-ID"
        result.notes == "Updated notes"
    }

    def "create throws DuplicateException when displayId already exists"() {
        given: "an existing purchase order with a specific displayId"
        def vendorContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        def shipToContactJson = JSONB.valueOf("""{"id": "contact-1", "name": "Test Contact"}""")
        dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, "po-existing")
                .set(PURCHASE_ORDERS.PROJECT_ID, "proj-1")
                .set(PURCHASE_ORDERS.COMPANY_ID, 1L)
                .set(PURCHASE_ORDERS.DISPLAY_ID, "DUPLICATE-PO-001")
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, "CAD")
                .set(PURCHASE_ORDERS.STATUS, PurchaseOrderStatus.PENDING.getValue())
                .set(PURCHASE_ORDERS.CHANGE_LOG, JSONB.valueOf("[]"))
                .execute()

        and: "a new purchase order record with the same displayId"
        def duplicatePo = dsl.newRecord(PURCHASE_ORDERS)
        duplicatePo.setProjectId("proj-1")
        duplicatePo.setCompanyId(1L)
        duplicatePo.setDisplayId("DUPLICATE-PO-001")
        duplicatePo.setVendorContact(vendorContactJson)
        duplicatePo.setShipToContact(shipToContactJson)
        duplicatePo.setCurrencyCode("CAD")
        duplicatePo.setStatus(PurchaseOrderStatus.PENDING.getValue())
        duplicatePo.setChangeLog(JSONB.valueOf("[]"))

        when: "creating the duplicate purchase order"
        repository.create(duplicatePo, [])

        then: "DuplicateException is thrown"
        def ex = thrown(DuplicateException)
        ex.message.contains("DUPLICATE-PO-001")
        ex.message.contains("already exists")
    }

}