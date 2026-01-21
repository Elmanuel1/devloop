package com.tosspaper.purchaseorder

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.ApiError
import com.tosspaper.common.ApiErrorMessages
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.generated.model.Contact
import com.tosspaper.generated.model.PurchaseOrder
import com.tosspaper.generated.model.PurchaseOrderCreate
import com.tosspaper.generated.model.PurchaseOrderItem
import com.tosspaper.generated.model.PurchaseOrderList
import com.tosspaper.generated.model.PurchaseOrderStatus
import com.tosspaper.generated.model.PurchaseOrderStatusUpdate
import com.tosspaper.generated.model.PurchaseOrderUpdate
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord
import org.jooq.DSLContext
import org.spockframework.spring.EnableSharedInjection
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import spock.lang.Shared

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

import static com.tosspaper.models.jooq.Tables.*

@EnableSharedInjection
class PurchaseOrderControllerSpec extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate

    @Autowired
    @Shared
    private DSLContext dsl

    @Autowired
    private ObjectMapper objectMapper

    @Shared
    def testCompanyId = 1L
    @Shared
    def testProjectId = "proj-1"
    @Shared
    def testContactId = "contact-1"

    def setup() {
        dsl.insertInto(COMPANIES)
                .set(COMPANIES.ID, testCompanyId)
                .set(COMPANIES.NAME, "Test Co")
                .set(COMPANIES.EMAIL, TestSecurityConfiguration.TEST_USER_EMAIL)
                .execute()

        dsl.insertInto(PROJECTS)
                .set(PROJECTS.ID, testProjectId)
                .set(PROJECTS.COMPANY_ID, testCompanyId)
                .set(PROJECTS.KEY, "P1")
                .set(PROJECTS.NAME, "Project 1")
                .set(PROJECTS.STATUS, "ACTIVE")
                .execute()

        dsl.insertInto(CONTACTS)
                .set(CONTACTS.ID, testContactId)
                .set(CONTACTS.COMPANY_ID, testCompanyId)
                .set(CONTACTS.NAME, "Contact 1")
                .set(CONTACTS.EMAIL, "c1@test.com")
                .set(CONTACTS.STATUS, "active")
                .execute()
    }

    def cleanup() {
        dsl.deleteFrom(PURCHASE_ORDER_ITEMS).execute()
        dsl.deleteFrom(PURCHASE_ORDERS).execute()
        dsl.deleteFrom(CONTACTS).execute()
        dsl.deleteFrom(PROJECTS).execute()
        dsl.deleteFrom(COMPANIES).execute()

    }

    private void createPurchaseOrder(Map args) {
        def id = args.id as String
        def displayId = args.displayId as String
        def projectId = args.get('projectId', testProjectId) as String
        def vendorContactId = args.get('vendorContactId', testContactId) as String
        def status = args.get('status', 'pending') as String
        def orderDate = args.get('orderDate', null) as LocalDate
        def dueDate = args.get('dueDate', null) as LocalDate
        def createdAt = args.get('createdAt', null) as OffsetDateTime
        def notes = args.get('notes', "This is a test purchase order") as String
        def currencyCode = args.get('currencyCode', 'CAD') as String

        // Create vendor and ship-to contacts as JSONB
        def vendorContactJson = org.jooq.JSONB.valueOf("""{"id": "${vendorContactId}", "name": "Contact 1"}""")
        def shipToContactJson = org.jooq.JSONB.valueOf("""{"id": "${vendorContactId}", "name": "Contact 1"}""")

        def query = dsl.insertInto(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.ID, id)
                .set(PURCHASE_ORDERS.DISPLAY_ID, displayId)
                .set(PURCHASE_ORDERS.COMPANY_ID, testCompanyId)
                .set(PURCHASE_ORDERS.PROJECT_ID, projectId)
                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                .set(PURCHASE_ORDERS.STATUS, status)
                .set(PURCHASE_ORDERS.NOTES, notes)
                .set(PURCHASE_ORDERS.CURRENCY_CODE, currencyCode)

        if (orderDate != null) {
            query.set(PURCHASE_ORDERS.ORDER_DATE, orderDate)
        }
        if (dueDate != null) {
            query.set(PURCHASE_ORDERS.DUE_DATE, dueDate)
        }
        if (createdAt != null) {
            query.set(PURCHASE_ORDERS.CREATED_AT, createdAt)
        }

        query.execute()
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
                .set(CONTACTS.STATUS, "active")
                .execute()
    }

    private HttpHeaders createAuthHeadersWithContext(String csrfToken = null, String csrfCookie = null) {
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", testCompanyId.toString())
        return headers
    }

    def "should update purchase order status"() {
        given: "an existing purchase order"
        def poId = "po-1"
        createPurchaseOrder(id: poId, displayId: "PO-001")

        and: "an authenticated request to update the status"
        def statusUpdate = new PurchaseOrderStatusUpdate(status: PurchaseOrderStatus.IN_PROGRESS)
        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>(objectMapper.writeValueAsString(statusUpdate), headers)

        when: "the update status endpoint is called"
        def response = restTemplate.exchange(
                "/v1/purchase-orders/$poId/status",
                HttpMethod.PUT,
                entity,
                String
        )

        then: "the request is successful"
        response.statusCode == HttpStatus.NO_CONTENT

        and: "the database record is updated"
        def updatedRecord = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        updatedRecord.status == "in_progress"
    }

    def "should update purchase order"() {
        given: "an existing purchase order"
        def newContactId = "contact-new"
        def shipToContactId = "shipto-new"
        createContact(newContactId, testCompanyId, "Contact New", "cnew@test.com")
        createContact(shipToContactId, testCompanyId, "ShipTo New", "shipto@test.com")
        def poId = "po-2"
        def orderDate = LocalDate.now()
        createPurchaseOrder(id: poId, displayId: "PO-002", orderDate: orderDate)

        and: "an authenticated request to update a few fields"
        def newDueDate = LocalDate.now().plusDays(10)
        def poUpdate = new PurchaseOrderUpdate(vendorContact: new Contact(id: newContactId), shipToContact: new Contact(id: shipToContactId), dueDate: newDueDate, notes: "Updated notes")
        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>(objectMapper.writeValueAsString(poUpdate), headers)

        when: "the update endpoint is called"
        def response = restTemplate.exchange(
                "/v1/purchase-orders/$poId",
                HttpMethod.PUT,
                entity,
                String
        )

        then: "the request is successful and returns the updated purchase order with all fields"
        response.statusCode == HttpStatus.NO_CONTENT
        def po = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        po.vendorContact != null  // JSONB field containing contact info
        po.dueDate == newDueDate
        po.id == poId
        po.displayId == "PO-002"
        po.status == PurchaseOrderStatus.PENDING.value
        po.orderDate == orderDate
        po.notes == "Updated notes"

        and: "the database record is also updated"
        def updatedRecord = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        updatedRecord.vendorContact != null  // JSONB field containing contact info
        updatedRecord.dueDate == newDueDate
        updatedRecord.notes == "Updated notes"
    }

    def "should return 400 for invalid status update request body"() {
        given: "an authenticated request with a malformed body"
        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>("{\"invalid\":\"json\"}", headers)

        when: "the update status endpoint is called"
        def response = restTemplate.exchange(
                "/v1/purchase-orders/po-1/status",
                HttpMethod.PUT,
                entity,
                String
        )

        then: "a 400 Bad Request status is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "should return bad request for invalid status transition"() {
        given: "a in_progress purchase order"
        def poId = "po-3"
        createPurchaseOrder(id: poId, displayId: "PO-003", status: "in_progress")

        and: "an authenticated request to update to a disallowed status"
        def statusUpdate = new PurchaseOrderStatusUpdate(status: PurchaseOrderStatus.PENDING)
        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>(objectMapper.writeValueAsString(statusUpdate), headers)

        when: "the update status endpoint is called"
        def response = restTemplate.exchange(
                "/v1/purchase-orders/$poId/status",
                HttpMethod.PUT,
                entity,
                ApiError
        )

        then: "the response is Bad Request with the correct message"
        response.statusCode == HttpStatus.BAD_REQUEST
        response.body.code() == "illegal_state_transition"
        response.body.message() == String.format(ApiErrorMessages.PURCHASE_ORDER_ILLEGAL_STATE_TRANSITION, "in_progress", "pending")
    }

    def "should return a purchase order by id"() {
        given: "a purchase order with all fields exists in the database"
        def poId = "po-4"
        def orderDate = LocalDate.now().minusDays(10)
        def dueDate = LocalDate.now().plusDays(20)
        createPurchaseOrder(id: poId, displayId: "PO-004", orderDate: orderDate, dueDate: dueDate)

        and: "it has an item"
        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.ID, "item-1")
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, poId)
                .set(PURCHASE_ORDER_ITEMS.NAME, "Test Item")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 5)
                .execute()

        and: "an authenticated request"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the get by id endpoint is called"
        def response = restTemplate.exchange(
                "/v1/purchase-orders/$poId",
                HttpMethod.GET,
                entity,
                PurchaseOrder
        )

        then: "the request is successful and returns the full purchase order"
        response.statusCode == HttpStatus.OK
        def po = response.body
        po.id == poId
        po.displayId == "PO-004"
        po.projectId == testProjectId
        po.vendorContact != null
        po.vendorContact.id == testContactId
        po.status == PurchaseOrderStatus.PENDING
        po.orderDate == orderDate
        po.dueDate == dueDate
        po.items.size() == 1
        po.items[0].id == "item-1"
        po.items[0].name == "Test Item"
        po.items[0].quantity == 5
        po.createdAt.getOffset().toString() == "Z"
        po.updatedAt == null
        po.notes == "This is a test purchase order"
    }

    def "should return 404 when purchase order is not found"() {
        given: "an authenticated request for a nonexistent purchase order"
        def poId = "po-nonexistent"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the endpoint is called"
        def response = restTemplate.exchange(
                "/v1/purchase-orders/$poId",
                HttpMethod.GET,
                entity,
                String
        )

        then: "a 404 Not Found status is returned"
        response.statusCode == HttpStatus.NOT_FOUND
    }

    def "should get purchase orders for a project"() {
        given: "multiple purchase orders exist for different projects"
        def proj2 = "proj-2"
        def proj3 = "proj-3"
        createProject(proj2, testCompanyId, "P2", "Project 2")
        createProject(proj3, testCompanyId, "P3", "Project 3")
        createPurchaseOrder(id: "po-6", displayId: "PO-006", projectId: proj2, status: "pending")
        createPurchaseOrder(id: "po-7", displayId: "PO-007", projectId: proj2, status: "in_progress")
        createPurchaseOrder(id: "po-8", displayId: "PO-008", projectId: proj3, status: "pending")

        and: "an authenticated request for a specific project"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the get for project endpoint is called"
        def response = restTemplate.exchange(
                "/v1/projects/proj-2/purchase-orders",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the request is successful and returns only the project's purchase orders"
        response.statusCode == HttpStatus.OK
        response.body.data.size() == 2
        response.body.data.every { it.projectId == "proj-2" }
    }

    def "should paginate through all purchase orders defaulting to DESC" () {
        given: "three purchase orders for the same project with distinct timestamps"
        def now = OffsetDateTime.now(ZoneOffset.UTC)
        createPurchaseOrder(id: "po-p1", displayId: "PO-P1", createdAt: now)
        createPurchaseOrder(id: "po-p2", displayId: "PO-P2", createdAt: now.plusMinutes(1))
        createPurchaseOrder(id: "po-p3", displayId: "PO-P3", createdAt: now.plusMinutes(2))

        and: "an authenticated request"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the first page is requested"
        def response1 = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?pageSize=1&page=1",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the newest record is returned with pagination metadata"
        response1.statusCode == HttpStatus.OK
        response1.body.data.size() == 1
        response1.body.data[0].id == "po-p3"
        response1.body.pagination.page == 1
        response1.body.pagination.pageSize == 1
        response1.body.pagination.totalItems == 3

        when: "the second page is requested"
        def response2 = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?pageSize=1&page=2",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the middle record is returned"
        response2.statusCode == HttpStatus.OK
        response2.body.data.size() == 1
        response2.body.data[0].id == "po-p2"
        response2.body.pagination.page == 2

        when: "the third page is requested"
        def response3 = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?pageSize=1&page=3",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the oldest record is returned"
        response3.statusCode == HttpStatus.OK
        response3.body.data.size() == 1
        response3.body.data[0].id == "po-p1"
        response3.body.pagination.page == 3
    }

    def "should filter purchase orders by status"() {
        given: "purchase orders with different statuses"
        createPurchaseOrder(id: "po-f1", displayId: "PO-F1", status: "pending")
        createPurchaseOrder(id: "po-f2", displayId: "PO-F2", status: "in_progress")

        and: "an authenticated request to filter by status"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the endpoint is called with a status filter"
        def response = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?status=in_progress",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "only the matching purchase order is returned"
        response.statusCode == HttpStatus.OK
        response.body.data.size() == 1
        response.body.data[0].id == "po-f2"
        response.body.data[0].status == PurchaseOrderStatus.IN_PROGRESS
    }

    def "should filter purchase orders by displayId"() {
        given: "purchase orders with different displayIds"
        createPurchaseOrder(id: "po-d1", displayId: "PO-FILTER-1")
        createPurchaseOrder(id: "po-d2", displayId: "PO-FILTER-2")

        and: "an authenticated request to filter by displayId"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the endpoint is called with a displayId filter"
        def response = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?displayId=PO-FILTER-2",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "only the matching purchase order is returned"
        response.statusCode == HttpStatus.OK
        response.body.data.size() == 1
        response.body.data[0].id == "po-d2"
    }

    def "should filter purchase orders by dueDate"() {
        given: "purchase orders with different dueDates"
        def date1 = LocalDate.now().plusDays(10)
        def date2 = LocalDate.now().plusDays(20)
        createPurchaseOrder(id: "po-date1", displayId: "PO-DATE1", dueDate: date1)
        createPurchaseOrder(id: "po-date2", displayId: "PO-DATE2", status: "in_progress", dueDate: date2)

        and: "an authenticated request to filter by dueDate"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())
        def date2Utc = date2.atStartOfDay().atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        when: "the endpoint is called with a dueDate filter"
        def response = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?dueDate=$date2Utc",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "only the matching purchase order is returned"
        response.statusCode == HttpStatus.OK
        response.body.data.size() == 1
        response.body.data[0].id == "po-date2"
    }

    def "should return empty items array for purchase orders without items"() {
        given: "a purchase order without any items"
        def poId = "po-no-items"
        createPurchaseOrder(id: poId, displayId: "PO-NO-ITEMS", notes: "Purchase order without items")

        and: "another purchase order with items for comparison"
        def poWithItemsId = "po-with-items"
        createPurchaseOrder(id: poWithItemsId, displayId: "PO-WITH-ITEMS", notes: "Purchase order with items")

        // Add an item to the second purchase order
        dsl.insertInto(PURCHASE_ORDER_ITEMS)
                .set(PURCHASE_ORDER_ITEMS.ID, "item-1")
                .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, poWithItemsId)
                .set(PURCHASE_ORDER_ITEMS.NAME, "Test Item")
                .set(PURCHASE_ORDER_ITEMS.QUANTITY, 2)
                .set(PURCHASE_ORDER_ITEMS.UNIT_PRICE, 10.50)
                .set(PURCHASE_ORDER_ITEMS.NOTES, "Test item notes")
                .execute()

        and: "an authenticated request"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the single PO endpoints are called to check items"
        def responseNoItems = restTemplate.exchange(
                "/v1/purchase-orders/$poId",
                HttpMethod.GET,
                entity,
                PurchaseOrder
        )
        def responseWithItems = restTemplate.exchange(
                "/v1/purchase-orders/$poWithItemsId",
                HttpMethod.GET,
                entity,
                PurchaseOrder
        )

        then: "the requests are successful"
        responseNoItems.statusCode == HttpStatus.OK
        responseWithItems.statusCode == HttpStatus.OK

        and: "the purchase order without items has an empty items array"
        def poWithoutItems = responseNoItems.body
        poWithoutItems != null
        poWithoutItems.items != null
        poWithoutItems.items.size() == 0
        poWithoutItems.notes == "Purchase order without items"

        and: "the purchase order with items has the correct items"
        def poWithItems = responseWithItems.body
        poWithItems != null
        poWithItems.items != null
        poWithItems.items.size() == 1
        poWithItems.items[0].id == "item-1"
        poWithItems.items[0].name == "Test Item"
        poWithItems.items[0].quantity == 2
        poWithItems.items[0].unitPrice == 10.50
        poWithItems.items[0].notes == "Test item notes"
    }

    def "should paginate backwards through all purchase orders"() {
        given: "three purchase orders for the same project with distinct timestamps"
        def now = OffsetDateTime.now(ZoneOffset.UTC)
        createPurchaseOrder(id: "po-b1", displayId: "PO-B1", createdAt: now.minusMinutes(2))
        createPurchaseOrder(id: "po-b2", displayId: "PO-B2", createdAt: now.minusMinutes(1))
        createPurchaseOrder(id: "po-b3", displayId: "PO-B3", createdAt: now)

        and: "an authenticated request"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the first page is requested in descending order"
        def response1 = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?pageSize=1&page=1",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the newest record is returned with pagination metadata"
        response1.statusCode == HttpStatus.OK
        response1.body.data.size() == 1
        response1.body.data[0].id == "po-b3"
        response1.body.pagination.page == 1
        response1.body.pagination.totalItems == 3

        when: "the second page is requested"
        def response2 = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?pageSize=1&page=2",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the middle record is returned"
        response2.statusCode == HttpStatus.OK
        response2.body.data.size() == 1
        response2.body.data[0].id == "po-b2"
        response2.body.pagination.page == 2

        when: "the third page is requested"
        def response3 = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders?pageSize=1&page=3",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the oldest record is returned"
        response3.statusCode == HttpStatus.OK
        response3.body.data.size() == 1
        response3.body.data[0].id == "po-b1"
        response3.body.pagination.page == 3
    }

    def "should create a purchase order successfully"() {
        given: "a valid purchase order creation request"
        def createDto = new PurchaseOrderCreate(
                vendorContact: new Contact(id: testContactId),
                shipToContact: new Contact(id: testContactId),
                dueDate: LocalDate.now(),
                items: [],
                notes: "Initial notes",
                currencyCode: "CAD"
        )
        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>(objectMapper.writeValueAsString(createDto), headers)

        when: "the create endpoint is called"
        def response = restTemplate.exchange(
                "/v1/projects/proj-1/purchase-orders",
                HttpMethod.POST,
                entity,
                String
        )

        then: "the request is successful with 201 Created status"
        response.statusCode == HttpStatus.CREATED
        
        and: "the response has a Location header"
        response.headers.getLocation() != null
        response.headers.getLocation().toString().contains("/v1/purchase-orders/")
        
        and: "the response body is empty"
        response.body == null || response.body.isEmpty()
    }

    def "should create and retrieve purchase order with metadata as proper JSON object"() {
        given: "a purchase order creation request with metadata"
        def metadata = [
            "color": "red",
            "size": "large", 
            "priority": "high",
            "tags": ["urgent", "special"]
        ]
        def createDto = new PurchaseOrderCreate(
                vendorContact: new Contact(id: testContactId),
                shipToContact: new Contact(id: testContactId),
                notes: "PO with metadata",
                metadata: metadata,
                currencyCode: "CAD"
        )
        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>(objectMapper.writeValueAsString(createDto), headers)

        when: "the create endpoint is called"
        def createResponse = restTemplate.exchange(
                "/v1/projects/$testProjectId/purchase-orders",
                HttpMethod.POST,
                entity,
                String
        )

        then: "the request is successful"
        createResponse.statusCode == HttpStatus.CREATED
        createResponse.headers.getLocation() != null

        when: "extracting the purchase order ID from the Location header"
        def locationPath = createResponse.headers.getLocation().path
        def poId = locationPath.substring(locationPath.lastIndexOf('/') + 1)

        and: "retrieving the purchase order by ID"
        def getEntity = new HttpEntity<>(createAuthHeadersWithContext())
        def getResponse = restTemplate.exchange(
                "/v1/purchase-orders/${poId}",
                HttpMethod.GET,
                getEntity,
                PurchaseOrder
        )

        then: "the metadata is returned as a proper Map object"
        getResponse.statusCode == HttpStatus.OK
        def retrievedPo = getResponse.body
        retrievedPo.metadata instanceof Map
        retrievedPo.metadata["color"] == "red"
        retrievedPo.metadata["size"] == "large"
        retrievedPo.metadata["priority"] == "high" 
        retrievedPo.metadata["tags"] instanceof List
        retrievedPo.metadata["tags"] == ["urgent", "special"]

        and: "when serialized to JSON, it should be a proper JSON object, not a string"
        def jsonResponse = objectMapper.writeValueAsString(retrievedPo.metadata)
        def parsedMetadata = objectMapper.readValue(jsonResponse, Map)
        parsedMetadata["color"] == "red"
        parsedMetadata["size"] == "large"
    }

    def "should update purchase order metadata as proper JSON object"() {
        given: "an existing purchase order with contacts"
        def vendorId = "vendor-meta"
        def shipToId = "shipto-meta"
        createContact(vendorId, testCompanyId, "Vendor Meta", "vendor@test.com")
        createContact(shipToId, testCompanyId, "ShipTo Meta", "shipto@test.com")
        def poId = "po-metadata-update"
        createPurchaseOrder(id: poId, displayId: "PO-META-UPDATE")

        and: "an update request with new metadata"
        def newMetadata = [
            "department": "engineering",
            "budget": 5000,
            "approved": true,
            "reviewers": ["john.doe", "jane.smith"]
        ]
        def updateDto = new PurchaseOrderUpdate(
                vendorContact: new Contact(id: vendorId),
                shipToContact: new Contact(id: shipToId),
                metadata: newMetadata,
                notes: "Updated with new metadata"
        )
        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>(objectMapper.writeValueAsString(updateDto), headers)

        when: "the update endpoint is called"
        def updateResponse = restTemplate.exchange(
                "/v1/purchase-orders/$poId",
                HttpMethod.PUT,
                entity,
                PurchaseOrder
        )

        then: "the request is successful"
        updateResponse.statusCode == HttpStatus.NO_CONTENT

        and: "metadata is returned as a proper Map object with correct values"
        def updatedPo = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(poId)).fetchOne()
        def metadata = new ObjectMapper().readValue(updatedPo.metadata.data(), Map)
        metadata["department"] == "engineering"
        metadata["budget"] == 5000
        metadata["approved"] == true
        metadata["reviewers"] instanceof List
        metadata["reviewers"] == ["john.doe", "jane.smith"]

        and: "other fields are preserved"
        updatedPo.id == poId
        updatedPo.displayId == "PO-META-UPDATE"
        updatedPo.notes == "Updated with new metadata"
    }

    def "should create purchase order items with auto-generated IDs"() {
        given: "a purchase order creation request with items"
        def createDto = new PurchaseOrderCreate(
                vendorContact: new Contact(id: testContactId),
                shipToContact: new Contact(id: testContactId),
                notes: "Purchase order with items",
                currencyCode: "CAD"
        )

        // Add items without IDs (simulating API request)
        def item1 = new PurchaseOrderItem(name: "Test Item 1", quantity: 2)
        item1.setUnitPrice(25.50)
        item1.setNotes("First test item")

        def item2 = new PurchaseOrderItem(name: "Test Item 2", quantity: 1)
        item2.setUnitPrice(100.00)
        item2.setNotes("Second test item")

        createDto.setItems([item1, item2])

        def headers = createAuthHeadersWithContext()
        headers.add("Content-Type", "application/json")
        def entity = new HttpEntity<>(objectMapper.writeValueAsString(createDto), headers)

        when: "the purchase order is created"
        def response = restTemplate.exchange(
                "/v1/projects/proj-1/purchase-orders",
                HttpMethod.POST,
                entity,
                String
        )

        then: "the response is successful"
        response.statusCode == HttpStatus.CREATED
        response.headers.getLocation() != null

        when: "extracting the purchase order ID from the Location header"
        def locationPath = response.headers.getLocation().path
        def poId = locationPath.substring(locationPath.lastIndexOf('/') + 1)

        and: "retrieving the created purchase order"
        def getEntity = new HttpEntity<>(createAuthHeadersWithContext())
        def getResponse = restTemplate.exchange(
                "/v1/purchase-orders/${poId}",
                HttpMethod.GET,
                getEntity,
                PurchaseOrder
        )

        then: "the purchase order is retrieved successfully"
        getResponse.statusCode == HttpStatus.OK
        def createdPo = getResponse.body

        and: "the purchase order items have auto-generated IDs"
        createdPo.items.size() == 2
        createdPo.items.each { item ->
            assert item.id != null : "Item ID should not be null"
            assert item.id != "" : "Item ID should not be empty"
            assert item.id.length() > 10 : "Item ID should be a proper ULID"
        }

        and: "the items have correct data"
        def firstItem = createdPo.items.find { it.name == "Test Item 1" }
        firstItem != null
        firstItem.quantity == 2
        firstItem.unitPrice == 25.50
        firstItem.notes == "First test item"

        def secondItem = createdPo.items.find { it.name == "Test Item 2" }
        secondItem != null
        secondItem.quantity == 1
        secondItem.unitPrice == 100.00
        secondItem.notes == "Second test item"
    }

    def "should get all purchase orders across all projects"() {
        given: "multiple purchase orders exist for different projects"
        def proj2 = "proj-2"
        createProject(proj2, testCompanyId, "P2", "Project 2")
        createPurchaseOrder(id: "po-all-1", displayId: "PO-ALL-001", projectId: testProjectId, status: "pending")
        createPurchaseOrder(id: "po-all-2", displayId: "PO-ALL-002", projectId: proj2, status: "in_progress")

        and: "an authenticated request"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the get all purchase orders endpoint is called without project filter"
        def response = restTemplate.exchange(
                "/v1/purchase-orders",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "the request is successful and returns purchase orders from all projects"
        response.statusCode == HttpStatus.OK
        response.body.data.size() >= 2
    }

    def "should get all purchase orders with status filter"() {
        given: "purchase orders with different statuses"
        createPurchaseOrder(id: "po-status-1", displayId: "PO-STATUS-1", status: "pending")
        createPurchaseOrder(id: "po-status-2", displayId: "PO-STATUS-2", status: "in_progress")

        and: "an authenticated request to filter by status"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the endpoint is called with a status filter"
        def response = restTemplate.exchange(
                "/v1/purchase-orders?status=pending",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "only the matching purchase order is returned"
        response.statusCode == HttpStatus.OK
        response.body.data.every { it.status == PurchaseOrderStatus.PENDING }
    }

    def "should get all purchase orders with null status"() {
        given: "purchase orders exist"
        createPurchaseOrder(id: "po-no-status-1", displayId: "PO-NO-STATUS-1", status: "pending")
        createPurchaseOrder(id: "po-no-status-2", displayId: "PO-NO-STATUS-2", status: "in_progress")

        and: "an authenticated request without status filter"
        def entity = new HttpEntity<>(createAuthHeadersWithContext())

        when: "the endpoint is called without status filter"
        def response = restTemplate.exchange(
                "/v1/purchase-orders",
                HttpMethod.GET,
                entity,
                PurchaseOrderList
        )

        then: "all purchase orders are returned"
        response.statusCode == HttpStatus.OK
        response.body.data.size() >= 2
    }
}