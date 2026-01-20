# Mocking Patterns

## 1. Mocks for Repositories, REAL Mappers

```groovy
class ContactServiceSpec extends Specification {
    ContactRepository repository
    ContactMapper mapper  // Use REAL mapper instance
    NotificationService notificationService
    ContactService service

    def setup() {
        repository = Mock()
        mapper = new ContactMapper()  // REAL instance - test field mapping!
        notificationService = Mock()
        service = new ContactServiceImpl(repository, mapper, notificationService)
    }
}
```

**Why:** REAL mappers catch bugs when fields are added/removed. Never mock mappers.

---

## 2. Mocking Static Methods (SecurityUtils)

```groovy
import org.mockito.MockedStatic
import org.mockito.Mockito

class ItemServiceSpec extends Specification {
    MockedStatic<SecurityUtils> securityUtilsMock

    def setup() {
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class)
        securityUtilsMock.when({ SecurityUtils.getSubjectFromJwt() }).thenReturn("test@example.com")
    }

    def cleanup() {
        securityUtilsMock?.close()  // CRITICAL: always close in cleanup
    }
}
```

---

## 3. ObjectMapper Configuration

```groovy
def setup() {
    objectMapper = new ObjectMapper()
    objectMapper.findAndRegisterModules()  // Required for Java 8 date/time types
    service = new MyServiceImpl(..., objectMapper)
}
```

---

## 4. Use Real Instances for Value Objects

```groovy
// ❌ WRONG — Don't mock records/value objects
def companyInfo = Mock(CompanyLookupService.CompanyBasicInfo) { getAssignedEmail() >> "inbox@company.com" }

// ✅ RIGHT — Create real record instance
def companyInfo = new CompanyLookupService.CompanyBasicInfo(companyId, "inbox@company.com", "owner@company.com", "Test Company")
```

---

## 5. Create COMPLETE Test Objects

```groovy
// ❌ WRONG — Sparse object hides mapping bugs
private static Item createItem(String id) {
    Item.builder().id(id).build()
}

// ✅ RIGHT — Complete object verifies all fields map correctly
private static Item createItem(String id, Long companyId) {
    Item.builder()
        .id(id)
        .companyId(companyId)
        .connectionId("conn-1")
        .name("Test Product")
        .code("TEST-001")
        .unitPrice(new BigDecimal("99.99"))
        .active(true)
        .createdAt(OffsetDateTime.now())
        .build()
}
```

