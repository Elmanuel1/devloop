# Validation Tests (Controllers Only)

## When to Write Validation Tests

✅ Read OpenAPI spec for the endpoint
✅ Test each `required: true` field
✅ Test each `minLength/maxLength` constraint
✅ Test each `format` constraint (email, etc.)
✅ Test each `pattern` constraint
✅ Test each `minimum/maximum` constraint

---

## Separate Validation Spec

```
ContactControllerSpec.groovy       # Happy path tests
ContactValidationSpec.groovy       # Validation tests only
```

---

## BDD Formatting: Proper Nesting

**CRITICAL:** BDD blocks (`given:`, `when:`, `then:`, `and:`) are **parent blocks**. All statements within them **MUST be indented** to show they belong to that block.

---

## Validation Test Pattern

```groovy
class ContactValidationSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    DSLContext dsl

    def setup() {
        // Set up real database data - services will query this
        dsl.insertInto(Tables.COMPANIES)
            .set([id: 1L, name: "Test Company", email: "test@test.com", assigned_email: "test@dev-clientdocs.useassetiq.com"])
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        // Clean up in reverse order of foreign keys
        dsl.deleteFrom(Tables.CONTACTS).execute()
        dsl.deleteFrom(Tables.COMPANIES).execute()
    }

    def setupHeaders() {
        def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
        def headers = createAuthHeaders(csrfToken, csrfCookie)
        headers.add("X-Context-Id", "1")
        return headers
    }

    // OpenAPI: name is required
    def "POST /v1/contacts returns 400 when name is missing"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request without name"
            def createDto = new ContactCreate(tag: ContactTagEnum.SUPPLIER, email: "test@test.com")
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }

    // OpenAPI: name has @NotBlank validation
    def "POST /v1/contacts returns 400 when name is blank"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request with blank name"
            def createDto = new ContactCreate(name: "", tag: ContactTagEnum.SUPPLIER, email: "test@test.com")
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }

    // OpenAPI: email format: email
    def "POST /v1/contacts returns 400 when email is invalid format"() {
        given: "csrf and auth headers"
            def headers = setupHeaders()

        and: "request with invalid email"
            def createDto = new ContactCreate(name: "Test Contact", tag: ContactTagEnum.SUPPLIER, email: "not-an-email")
            def entity = new HttpEntity<>(createDto, headers)

        when: "creating contact"
            def response = restTemplate.exchange("/v1/contacts", HttpMethod.POST, entity, String)

        then: "returns 400 with validation error"
            response.statusCode == HttpStatus.BAD_REQUEST
            def error = objectMapper.readValue(response.body, Map)
            error.message != null
    }
}
```

**Note:** All statements under `given:`, `and:`, `when:`, `then:` are indented to show they belong to those blocks.

---

## OpenAPI to Test Mapping

| OpenAPI Property | Test Case |
|------------------|-----------|
| `required: true` | Omit field from request |
| `minLength: 1` | Send empty string `""` |
| `maxLength: N` | Send string of length N+1 |
| `format: email` | Send `"not-an-email"` |
| `format: uri` | Send `"not-a-uri"` |
| `pattern: "regex"` | Send value not matching pattern |
| `minimum: N` | Send value N-1 |
| `maximum: N` | Send value N+1 |
| `minItems: N` | Send array with N-1 items |
| `maxItems: N` | Send array with N+1 items |
| `enum: [A, B]` | Send value not in enum |

