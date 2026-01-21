package com.tosspaper.item

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.ApiError
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.generated.model.Item
import com.tosspaper.generated.model.ItemCreate
import com.tosspaper.generated.model.ItemList
import com.tosspaper.generated.model.ItemUpdate
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import spock.lang.Shared

import static com.tosspaper.models.jooq.Tables.AUTHORIZED_USERS
import static com.tosspaper.models.jooq.Tables.COMPANIES
import static com.tosspaper.models.jooq.Tables.ITEMS

class ItemControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    DSLContext dsl

    @Autowired
    ObjectMapper objectMapper

    @Shared
    CompaniesRecord company

    def setup() {
        dsl.deleteFrom(ITEMS).execute()
        dsl.deleteFrom(AUTHORIZED_USERS).execute()
        dsl.deleteFrom(COMPANIES).execute()

        // Create a company for the test user
        company = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Test Company")
                .set(COMPANIES.EMAIL, TestSecurityConfiguration.TEST_USER_EMAIL)
                .returning()
                .fetchOne()

        // Create authorized user entry for permission checks
        dsl.insertInto(AUTHORIZED_USERS)
                .set(AUTHORIZED_USERS.ID, "test-user-id")
                .set(AUTHORIZED_USERS.COMPANY_ID, company.getId())
                .set(AUTHORIZED_USERS.USER_ID, "test-supabase-user-id")
                .set(AUTHORIZED_USERS.EMAIL, TestSecurityConfiguration.TEST_USER_EMAIL)
                .set(AUTHORIZED_USERS.ROLE_ID, "owner")
                .set(AUTHORIZED_USERS.ROLE_NAME, "Owner")
                .execute()
    }

    // ==================== GET /v1/items ====================

    def "GET /v1/items returns all items for company"() {
        given: "items exist for the company"
        createItem("Item 1", "CODE-1", "Service", true)
        createItem("Item 2", "CODE-2", "Inventory", true)

        and: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(headers)

        when: "fetching items"
        def response = restTemplate.exchange("/v1/items", HttpMethod.GET, requestEntity, ItemList)

        then: "items are returned"
        response.statusCode == HttpStatus.OK
        response.body.data.size() == 2
        response.body.data*.name.containsAll(["Item 1", "Item 2"])
    }

    def "GET /v1/items filters by active status when provided"() {
        given: "active and inactive items"
        createItem("Active Item", "ACT-1", "Service", true)
        createItem("Inactive Item", "INACT-1", "Service", false)

        and: "authenticated request with active=true filter"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(headers)

        when: "fetching only active items"
        def response = restTemplate.exchange("/v1/items?active=true", HttpMethod.GET, requestEntity, ItemList)

        then: "only active items are returned"
        response.statusCode == HttpStatus.OK
        response.body.data.size() == 1
        response.body.data[0].name == "Active Item"
    }

    def "GET /v1/items returns empty list when no items exist"() {
        given: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(headers)

        when: "fetching items"
        def response = restTemplate.exchange("/v1/items", HttpMethod.GET, requestEntity, ItemList)

        then: "empty list is returned"
        response.statusCode == HttpStatus.OK
        response.body.data.isEmpty()
    }

    def "GET /v1/items filters out Category type items"() {
        given: "items including a Category type"
        createItem("Service Item", "SVC-1", "Service", true)
        createItem("Category Item", "CAT-1", "Category", true)

        and: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(headers)

        when: "fetching items"
        def response = restTemplate.exchange("/v1/items", HttpMethod.GET, requestEntity, ItemList)

        then: "Category items are filtered out"
        response.statusCode == HttpStatus.OK
        response.body.data.size() == 1
        response.body.data[0].name == "Service Item"
    }

    def "GET /v1/items returns 400 when X-Context-Id header is missing"() {
        when: "fetching items without context header"
        def response = restTemplate.getForEntity("/v1/items", String)

        then: "400 is returned with validation error"
        response.statusCode == HttpStatus.BAD_REQUEST
    }

    // ==================== GET /v1/items/{id} ====================

    def "GET /v1/items/{id} returns item by ID"() {
        given: "an existing item"
        def created = createItem("Test Item", "TEST-1", "Service", true)

        and: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(headers)

        when: "fetching item by ID"
        def response = restTemplate.exchange("/v1/items/${created.getId()}", HttpMethod.GET, requestEntity, Item)

        then: "item is returned"
        response.statusCode == HttpStatus.OK
        response.body.id == created.getId()
        response.body.name == "Test Item"
        response.body.code == "TEST-1"
        response.body.type == "Service"
    }

    def "GET /v1/items/{id} returns 404 for non-existent item"() {
        given: "authenticated request"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(headers)

        when: "fetching non-existent item"
        def response = restTemplate.exchange("/v1/items/non-existent-id", HttpMethod.GET, requestEntity, String)

        then: "404 is returned"
        response.statusCode == HttpStatus.NOT_FOUND
    }

    def "GET /v1/items/{id} returns 403 for item from different company"() {
        given: "an item from a different company"
        def otherCompany = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Other Company")
                .set(COMPANIES.EMAIL, "other@test.com")
                .returning()
                .fetchOne()
        def otherItem = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, otherCompany.getId())
                .set(ITEMS.NAME, "Other Company Item")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .returning()
                .fetchOne()

        and: "authenticated request for original company"
        def headers = createAuthHeaders()
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(headers)

        when: "fetching item from different company"
        def response = restTemplate.exchange("/v1/items/${otherItem.getId()}", HttpMethod.GET, requestEntity, String)

        then: "403 is returned"
        response.statusCode == HttpStatus.FORBIDDEN
    }

    // ==================== POST /v1/items ====================

    def "POST /v1/items creates a new item"() {
        given: "an item creation request"
        def itemCreate = new ItemCreate(
                name: "New Item",
                code: "NEW-001",
                description: "New item description",
                purchaseCost: new BigDecimal("50.00")
        )

        and: "authenticated request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(itemCreate, headers)

        when: "creating item"
        def response = restTemplate.postForEntity("/v1/items", requestEntity, Item)

        then: "item is created"
        response.statusCode == HttpStatus.CREATED
        response.body.id != null
        response.body.name == "New Item"
        response.body.code == "NEW-001"
        response.body.description == "New item description"
        response.body.purchaseCost == new BigDecimal("50.00")
        response.body.type == "Service"  // Default
        response.body.active == true  // Default
    }

    def "POST /v1/items creates item with minimal fields"() {
        given: "a minimal item creation request"
        def itemCreate = new ItemCreate(name: "Minimal Item")

        and: "authenticated request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(itemCreate, headers)

        when: "creating item"
        def response = restTemplate.postForEntity("/v1/items", requestEntity, Item)

        then: "item is created with defaults"
        response.statusCode == HttpStatus.CREATED
        response.body.name == "Minimal Item"
        response.body.type == "Service"
        response.body.active == true
    }

    def "POST /v1/items returns 409 for duplicate item code"() {
        given: "an existing item with a code"
        createItem("Original Name", "DUP-CODE-001", "Service", true)

        and: "an item creation request with same code"
        def itemCreate = new ItemCreate(name: "Different Name", code: "DUP-CODE-001")

        and: "authenticated request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(itemCreate, headers)

        when: "creating item with duplicate code"
        def response = restTemplate.postForEntity("/v1/items", requestEntity, String)

        then: "409 is returned"
        response.statusCode == HttpStatus.CONFLICT
    }

    def "POST /v1/items returns 403 without authentication"() {
        given: "an item creation request without auth"
        def itemCreate = new ItemCreate(name: "Test Item")
        def headers = new HttpHeaders()
        headers.set("X-Context-Id", "1")
        def requestEntity = new HttpEntity<>(itemCreate, headers)

        when: "creating item without auth"
        def response = restTemplate.postForEntity("/v1/items", requestEntity, String)

        then: "403 is returned (CSRF validation fails for unauthenticated requests)"
        response.statusCode == HttpStatus.FORBIDDEN
    }

    // ==================== PUT /v1/items/{id} ====================

    def "PUT /v1/items/{id} updates an existing item"() {
        given: "an existing item"
        def created = createItem("Original Name", "ORIG-001", "Service", true)

        and: "an update request"
        def itemUpdate = new ItemUpdate(
                name: "Updated Name",
                code: "UPD-001",
                description: "Updated description",
                purchaseCost: new BigDecimal("75.00"),
                active: false
        )

        and: "authenticated request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(itemUpdate, headers)

        when: "updating item"
        def response = restTemplate.exchange("/v1/items/${created.getId()}", HttpMethod.PUT, requestEntity, Void)

        then: "item is updated"
        response.statusCode == HttpStatus.NO_CONTENT

        and: "changes are persisted"
        def updated = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(created.getId())).fetchOne()
        updated.getName() == "Updated Name"
        updated.getCode() == "UPD-001"
        updated.getDescription() == "Updated description"
        updated.getPurchaseCost() == new BigDecimal("75.00")
        updated.getActive() == false
    }

    def "PUT /v1/items/{id} updates only provided fields"() {
        given: "an existing item"
        def created = createItem("Original Name", "ORIG-001", "Service", true)
        dsl.update(ITEMS)
                .set(ITEMS.DESCRIPTION, "Original description")
                .set(ITEMS.PURCHASE_COST, new BigDecimal("50.00"))
                .where(ITEMS.ID.eq(created.getId()))
                .execute()

        and: "a partial update request"
        def itemUpdate = new ItemUpdate(name: "New Name Only")

        and: "authenticated request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(itemUpdate, headers)

        when: "updating item"
        def response = restTemplate.exchange("/v1/items/${created.getId()}", HttpMethod.PUT, requestEntity, Void)

        then: "item is updated"
        response.statusCode == HttpStatus.NO_CONTENT

        and: "only provided fields are changed"
        def updated = dsl.selectFrom(ITEMS).where(ITEMS.ID.eq(created.getId())).fetchOne()
        updated.getName() == "New Name Only"
        updated.getCode() == "ORIG-001"  // Unchanged
        updated.getDescription() == "Original description"  // Unchanged
        updated.getPurchaseCost() == new BigDecimal("50.00")  // Unchanged
    }

    def "PUT /v1/items/{id} returns 404 for non-existent item"() {
        given: "an update request"
        def itemUpdate = new ItemUpdate(name: "Updated Name")

        and: "authenticated request with CSRF"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(itemUpdate, headers)

        when: "updating non-existent item"
        def response = restTemplate.exchange("/v1/items/non-existent-id", HttpMethod.PUT, requestEntity, String)

        then: "404 is returned"
        response.statusCode == HttpStatus.NOT_FOUND
    }

    def "PUT /v1/items/{id} returns 403 for item from different company"() {
        given: "an item from a different company"
        def otherCompany = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Other Company")
                .set(COMPANIES.EMAIL, "other2@test.com")
                .returning()
                .fetchOne()
        def otherItem = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, otherCompany.getId())
                .set(ITEMS.NAME, "Other Company Item")
                .set(ITEMS.TYPE, "Service")
                .set(ITEMS.ACTIVE, true)
                .returning()
                .fetchOne()

        and: "an update request"
        def itemUpdate = new ItemUpdate(name: "Hijacked Name")

        and: "authenticated request with CSRF for original company"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.set("X-Context-Id", company.getId().toString())
        def requestEntity = new HttpEntity<>(itemUpdate, headers)

        when: "updating item from different company"
        def response = restTemplate.exchange("/v1/items/${otherItem.getId()}", HttpMethod.PUT, requestEntity, String)

        then: "403 is returned"
        response.statusCode == HttpStatus.FORBIDDEN
    }

    // ==================== Helper Methods ====================

    private def createItem(String name, String code, String type, Boolean active) {
        dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, company.getId())
                .set(ITEMS.NAME, name)
                .set(ITEMS.CODE, code)
                .set(ITEMS.TYPE, type)
                .set(ITEMS.ACTIVE, active)
                .returning()
                .fetchOne()
    }
}
