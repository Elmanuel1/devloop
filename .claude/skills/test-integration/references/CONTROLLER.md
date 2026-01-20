# Controller Tests

## What to Test

✅ Happy path for all HTTP methods (GET, POST, PUT, DELETE)
✅ HTTP status codes
✅ Response body with all fields
✅ Authentication and authorization
✅ Jakarta Bean Validation (from OpenAPI spec)
✅ **MANDATORY**: Test all GlobalExceptionHandler cases (404, 403, 400)

## Important: Use Real Database Interactions

**Controller integration tests MUST interact with the real database/repository, NOT mocks.**

- ✅ Use `TestRestTemplate` with `RANDOM_PORT` web environment
- ✅ Set up test data in `setup()` using `DSLContext` 
- ✅ Verify database state in `then:` blocks
- ❌ Do NOT use `MockMvc` with `MOCK` web environment
- ❌ Do NOT mock repository services (CompanyLookupService, etc.)
- ❌ Do NOT use `@MockBean` for services that query the database

**Why?** Controller tests verify the full integration: HTTP → Controller → Service → Repository → Database. Mocking breaks this chain and hides real integration issues.

---

## Setup Pattern

```groovy
class ContactControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    DSLContext dsl

    @Shared
    Long companyId

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID
        // Set up real database data - services will query this
        dsl.insertInto(Tables.COMPANIES)
            .set([id: companyId, name: "Test Company", email: "test@test.com", assigned_email: "test@dev-clientdocs.useassetiq.com"])
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        // Clean up in reverse order of foreign keys
        dsl.deleteFrom(Tables.CONTACTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }
}
```

**Note:** Services like `CompanyLookupService` query the database via repositories. Ensure all required fields (e.g., `assigned_email`) are set in `setup()` so these queries succeed.

---

## BDD Formatting: Proper Nesting

**CRITICAL:** BDD blocks (`given:`, `when:`, `then:`, `and:`) are **parent blocks**. All statements within them **MUST be indented** to show they belong to that block.

```groovy
// ✅ CORRECT: Statements nested under BDD blocks
def "test name"() {
    given: "description"
        // All statements indented here
        def variable = value
        
    and: "additional setup"
        // More indented statements
        
    when: "action"
        // Action statements indented
        
    then: "verification"
        // Assertions indented
        condition == true
        
    and: "more verification"
        // More assertions indented
        anotherCondition == true
}

// ❌ WRONG: Statements not indented
def "test name"() {
    given: "description"
    def variable = value  // Not indented - WRONG!
    
    when: "action"
    def result = action()  // Not indented - WRONG!
    
    then: "verification"
    result == expected  // Not indented - WRONG!
}
```

---

## GET Request Pattern

```groovy
def "GET /v1/contacts/{id} returns contact with all fields"() {
    given: "a contact exists"
        def contactId = dsl.insertInto(Tables.CONTACTS)
            .set([company_id: companyId, name: "Test Contact", email: "test@test.com"])
            .returning(Tables.CONTACTS.ID)
            .fetchOne()
            .id

    and: "auth headers"
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        headers.add("X-Context-Id", companyId.toString())

    when: "fetching the contact"
        def response = restTemplate.exchange("/v1/contacts/${contactId}", HttpMethod.GET, new HttpEntity<>(headers), Contact)

    then: "returns 200 with all fields"
        response.statusCode == HttpStatus.OK
        with(response.body) {
            id == contactId
            name == "Test Contact"
            email == "test@test.com"
            tag != null
            status != null
            createdAt != null
        }
}
```

---

## POST Request Pattern (with CSRF)

```groovy
def "POST /v1/contacts creates contact"() {
    given: "csrf and auth headers"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", companyId.toString())

    and: "a valid request"
        def request = new ContactCreate(name: "New Contact", email: "new@test.com", tag: ContactTagEnum.SUPPLIER)
        def entity = new HttpEntity<>(request, headers)

    when: "creating the contact"
        def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, Contact)

    then: "returns 201 CREATED with all fields"
        response.statusCode == HttpStatus.CREATED
        with(response.body) {
            id != null
            name == "New Contact"
            email == "new@test.com"
            tag != null
            status != null
            createdAt != null
            updatedAt == null  // Not set on creation
        }
}
```

**Note:** All statements under `given:`, `and:`, `when:`, `then:` are indented to show they belong to those blocks.

---

## Test ALL Fields in Successful Calls

**MANDATORY:** For successful POST/GET/PUT operations, verify **ALL fields** returned in the response are correctly set.

```groovy
def "POST /v1/contacts should create a contact with all fields"() {
    given: "csrf and auth headers"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", companyId.toString())

    and: "a request with all fields"
        def address = new ContactCreate.Address()
        address.setAddress("123 Main St")
        address.setCity("Toronto")
        address.setStateOrProvince("ON")
        address.setPostalCode("M5H 2N2")
        address.setCountry("CA")
        
        def createDto = new ContactCreate(
            name: "Jane Smith",
            email: "jane.smith@supplier.com",
            phone: "+1-555-987-6543",
            tag: ContactTagEnum.VENDOR,
            notes: "Primary contact for billing",
            address: address,
            currencyCode: "USD"
        )
        def entity = new HttpEntity<>(createDto, headers)

    when: "creating the contact"
        def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

    then: "returns 201 CREATED with all fields set"
        response.statusCode == HttpStatus.CREATED
        def contact = objectMapper.readValue(response.body, Contact)
        with(contact) {
            id != null
            name == "Jane Smith"
            email == "jane.smith@supplier.com"
            phone == "+1-555-987-6543"
            tag == ContactTagEnum.VENDOR
            notes == "Primary contact for billing"
            status != null
            currencyCode == "USD"
            createdAt != null
            updatedAt == null  // Not set on creation
            provider == null  // Platform-created contact
            
            // Verify nested objects
            address != null
            address.address == "123 Main St"
            address.city == "Toronto"
            address.stateOrProvince == "ON"
            address.postalCode == "M5H 2N2"
            address.country == "CA"
        }
}
```

**Why:** Incomplete field assertions hide bugs. Every field must be verified to ensure the API contract is correctly implemented.

---

## PUT Request Pattern

```groovy
def "PUT /v1/contacts/{id} updates contact"() {
    given: "an existing contact"
        def contactId = dsl.insertInto(Tables.CONTACTS)
            .set([company_id: companyId, name: "Original", email: "orig@test.com"])
            .returning(Tables.CONTACTS.ID)
            .fetchOne()
            .id

    and: "csrf and auth headers"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", companyId.toString())

    and: "update request"
        def request = new ContactUpdate(name: "Updated Name")
        def entity = new HttpEntity<>(request, headers)

    when: "updating the contact"
        def response = restTemplate.exchange("/v1/contacts/${contactId}", HttpMethod.PUT, entity, Void)

    then: "returns 204 NO CONTENT"
        response.statusCode == HttpStatus.NO_CONTENT

    and: "contact is updated in database"
        def updated = dsl.selectFrom(Tables.CONTACTS).where(Tables.CONTACTS.ID.eq(contactId)).fetchOne()
        updated.name == "Updated Name"
}
```

---

## DELETE Request Pattern

```groovy
def "DELETE /v1/contacts/{id} deletes contact"() {
    given: "an existing contact"
        def contactId = dsl.insertInto(Tables.CONTACTS)
            .set([company_id: companyId, name: "To Delete", email: "delete@test.com"])
            .returning(Tables.CONTACTS.ID)
            .fetchOne()
            .id

    and: "csrf and auth headers"
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", companyId.toString())
        def entity = new HttpEntity<>(headers)

    when: "deleting the contact"
        def response = restTemplate.exchange("/v1/contacts/${contactId}", HttpMethod.DELETE, entity, Void)

    then: "returns 204 NO CONTENT"
        response.statusCode == HttpStatus.NO_CONTENT

    and: "contact no longer exists"
        def deleted = dsl.selectFrom(Tables.CONTACTS).where(Tables.CONTACTS.ID.eq(contactId)).fetchOne()
        deleted == null
}
```

---

## 404 Not Found Pattern

```groovy
def "GET /v1/contacts/{id} returns 404 for non-existent contact"() {
    given: "auth headers"
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        headers.add("X-Context-Id", companyId.toString())
        def entity = new HttpEntity<>(headers)

    when: "fetching non-existent contact"
        def response = restTemplate.exchange("/v1/contacts/999999", HttpMethod.GET, entity, String)

    then: "returns 404"
        response.statusCode == HttpStatus.NOT_FOUND
}
```

