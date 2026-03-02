# Integration Test Setup

## BaseIntegrationTest

```groovy
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfiguration.class)
abstract class BaseIntegrationTest extends Specification {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")

    static {
        postgres.start()
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.flyway.enabled", "true")
    }
}
```

---

## Cleanup Order

Clean up in reverse order of foreign keys:

```groovy
def cleanup() {
    dsl.deleteFrom(Tables.CONTACTS).execute()
    dsl.deleteFrom(Tables.COMPANIES).execute()
}
```

---

## CSRF Token Helper

```groovy
def initializeCsrfToken(TestRestTemplate restTemplate) {
    def response = restTemplate.getForEntity("/csrf", String)
    def csrfCookie = response.headers.getFirst("Set-Cookie")
    def csrfToken = extractCsrfToken(response.body)
    return [csrfToken, csrfCookie]
}

def createAuthHeaders(String csrfToken, String csrfCookie) {
    def headers = new HttpHeaders()
    headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
    headers.add("X-CSRF-TOKEN", csrfToken)
    headers.add("Cookie", csrfCookie)
    return headers
}
```

---

## Test Data Factory

Create a shared `TestDataFactory` for consistent test data:

```groovy
class TestDataFactory {
    static Contact createContact(Long companyId, String name = "Test Contact") {
        new Contact(
            companyId: companyId,
            name: name,
            email: "${name.toLowerCase().replace(' ', '.')}@test.com",
            phone: "+1-555-1234",
            type: ContactType.VENDOR,
            status: ContactStatus.ACTIVE
        )
    }

    static ContactCreate createContactCreate(String name = "New Contact") {
        new ContactCreate(
            name: name,
            email: "${name.toLowerCase().replace(' ', '.')}@test.com",
            type: ContactType.VENDOR
        )
    }
}
```

