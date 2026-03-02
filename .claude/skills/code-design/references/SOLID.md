# SOLID Principles

Five design principles for maintainable, testable object-oriented code.

## Best Practices (When to Apply)

- **Start simple, refactor when needed** — don't over-engineer upfront
- **Focus on change patterns** — apply SOLID where code changes frequently
- **If code is hard to test, it likely violates SOLID**
- **Use dependency injection frameworks** (Spring) to manage DIP

---

## S — Single Responsibility Principle

> A class should have only one reason to change.

**Signs of violation:**
- Class has multiple unrelated methods
- More than 5 dependencies
- Method comments like "// now do X" then "// now do Y"

**Fix:** Extract each responsibility into its own class.

```java
// ❌ BAD — handles calculation, persistence, and notification
public class OrderService {
    public Order createOrder(OrderRequest request) {
        BigDecimal total = calculateTotal(request.getItems());
        Order order = new Order();
        order.setTotal(total);
        orderRepository.save(order);
        emailService.sendOrderConfirmation(order);
        return order;
    }
}

// ✅ GOOD — each class has one job
public class OrderService {
    private final OrderCalculator calculator;
    private final OrderRepository repository;
    private final OrderNotificationService notifier;

    public Order createOrder(OrderRequest request) {
        Order order = calculator.calculate(request);
        Order saved = repository.save(order);
        notifier.notifyOrderCreated(saved);
        return saved;
    }
}
```

**Testability:** Each class tested in isolation with mocks.

---

## O — Open/Closed Principle

> Open for extension, closed for modification.

**Signs of violation:**
- Adding new feature requires editing existing if/else or switch
- Shotgun surgery across multiple files

**Fix:** Use interfaces/strategy pattern so new behavior is a new class.

```java
// ❌ BAD — must edit for every new discount type
public BigDecimal calculateDiscount(Order order) {
    if (order.getCustomerType() == VIP) return total.multiply(0.20);
    else if (order.getCustomerType() == REGULAR) return total.multiply(0.05);
    // add more else-if for each new type...
}

// ✅ GOOD — add new strategy without touching existing code
public interface DiscountStrategy {
    BigDecimal calculate(Order order);
}

@Component
public class VipDiscountStrategy implements DiscountStrategy { ... }

@Component
public class DiscountService {
    private final Map<CustomerType, DiscountStrategy> strategies;
    public BigDecimal calculateDiscount(Order order) {
        return strategies.get(order.getCustomerType()).calculate(order);
    }
}
```

**Testability:** Each strategy tested independently; new strategies don't break existing tests.

---

## L — Liskov Substitution Principle

> Subtypes must be substitutable for their base types without breaking behavior.

**Signs of violation:**
- Subclass throws `UnsupportedOperationException`
- Subclass changes semantics of inherited method

**Fix:** Use composition or separate interfaces instead of inheritance.

```java
// ❌ BAD — Square breaks Rectangle contract
public class Square extends Rectangle {
    public void setWidth(int w) { width = w; height = w; } // breaks LSP
}

// ✅ GOOD — separate types, common interface
public interface Shape { int getArea(); }
public class Rectangle implements Shape { ... }
public class Square implements Shape { ... }
```

**Testability:** Each implementation tested against its own contract.

---

## I — Interface Segregation Principle

> Clients should not depend on methods they don't use.

**Signs of violation:**
- Interface has 10+ methods
- Implementers throw `UnsupportedOperationException` for some methods

**Fix:** Split fat interface into focused ones.

```java
// ❌ BAD — clients must implement everything
public interface UserService {
    User create(...); User update(...); void delete(...);
    void sendWelcomeEmail(...); void resetPassword(...);
    Report generateUserReport();
}

// ✅ GOOD — focused interfaces
public interface UserCrudService { User create(...); User update(...); ... }
public interface UserNotificationService { void sendWelcomeEmail(...); ... }
public interface UserReportService { Report generate(); }
```

**Testability:** Mock only the interface you need.

---

## D — Dependency Inversion Principle

> Depend on abstractions, not concretions.

**Signs of violation:**
- Class instantiates its own dependencies (`new MySQLRepo()`)
- Field injection (`@Autowired` on fields)

**Fix:** Constructor injection with interfaces.

```java
// ❌ BAD — direct instantiation
public class OrderService {
    private MySQLOrderRepository repository = new MySQLOrderRepository();
}

// ✅ GOOD — constructor injection
public class OrderService {
    private final OrderRepository repository;
    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }
}
```

**Testability:** Easy to mock; no database needed for unit tests.

---

## DIP Best Practices (Spring/Lombok)

### Use @RequiredArgsConstructor Instead of Explicit Constructors

```java
// ❌ BAD — verbose explicit constructor
public class OrderService {
    private final OrderRepository repository;
    private final NotificationService notifier;

    public OrderService(OrderRepository repository, NotificationService notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }
}

// ✅ GOOD — Lombok generates constructor
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository repository;
    private final NotificationService notifier;
}
```

### Use ConfigurationProperties Instead of @Value

```java
// ❌ BAD — scattered @Value annotations
@Service
public class StorageService {
    @Value("${app.storage.root}") private String root;
    @Value("${app.storage.maxSize}") private int maxSize;
}

// ✅ GOOD — type-safe configuration class
@Data
@Validated
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {
    @NotBlank private String root;
    private int maxSize = 1024;
}

@RequiredArgsConstructor
@Service
public class StorageService {
    private final StorageProperties storageProperties;
}
```

### Use Context Objects Instead of Many Parameters

```java
// ❌ BAD — many individual parameters
public class ComparisonAdvisor {
    public ComparisonAdvisor(
            VfsService vfs,
            Long companyId,
            String documentId,
            String documentType,
            String poNumber,
            String extractionJson) { ... }
}

// ✅ GOOD — context record groups related data
public record ComparisonContext(
        PurchaseOrder purchaseOrder,
        ExtractionTask extractionTask
) {}

@RequiredArgsConstructor
public class ComparisonAdvisor {
    private final VfsService vfs;
    private final ComparisonContext context;
}
```

### Pass Domain Objects Instead of Syncing/Fetching

```java
// ❌ BAD — fetches data inside advisor (extra dependency, harder to test)
@RequiredArgsConstructor
public class ContextAdvisor {
    private final PoSyncService poSyncService; // extra dependency!
    private final VfsService vfs;

    public void prepare(String poNumber) {
        poSyncService.sync(poNumber); // fetches from DB
        vfs.save(...);
    }
}

// ✅ GOOD — receives domain object directly (fewer dependencies, easier to test)
@RequiredArgsConstructor
public class ContextAdvisor {
    private final VfsService vfs;
    private final ComparisonContext context; // has PurchaseOrder already

    public void prepare() {
        vfs.savePurchaseOrder(context.purchaseOrder());
    }
}
```

**Benefits:**
- Fewer dependencies = simpler testing
- No database calls in advisors = faster, deterministic tests
- Domain objects already validated by caller

---

## Common Anti-Patterns (Extraction API)

### Anti-Pattern 1: Manual DTO Construction in Controllers (SRP Violation)

Controllers should be thin — parsing headers and delegating to the service layer. Manually constructing response DTOs in the controller mixes UI/transport concerns with mapping logic.

```java
// ❌ BAD — controller manually constructs DTO
ExtractionResult result = extractionService.createExtraction(companyId, request);
ExtractionCreateResponse response = new ExtractionCreateResponse();
response.setId(result.extraction().getId());

// ✅ GOOD — delegate mapping to MapStruct mapper
ExtractionResult result = extractionService.createExtraction(companyId, request);
ExtractionCreateResponse response = extractionMapper.toCreateResponse(result.extraction());
```

**Fix:** Add a `toCreateResponse(Extraction)` method to the MapStruct mapper and inject the mapper into the controller.

---

### Anti-Pattern 2: Fire-and-Forget Update Then Re-Fetch (DIP + SRP Violation)

Updating records and then immediately re-fetching them to build the response is wasteful and error-prone. Use `RETURNING` in jOOQ to get the updated records atomically.

```java
// ❌ BAD — update fields, then re-fetch from DB
request.getUpdates().forEach(item -> {
    extractionFieldRepository.updateEditedValue(fieldId, jsonb);
});
extractionRepository.updateVersion(extractionId, expectedVersion);
List<ExtractionFieldsRecord> updatedFields = extractionFieldRepository.findAllByIds(fieldIds); // re-fetch!

// ✅ GOOD — bulkUpdateEditedValues uses RETURNING and returns updated records directly
BulkUpdateResult result = extractionFieldRepository.bulkUpdateEditedValues(updates, extractionId, expectedVersion);
// result.fields() already contains the updated records — no re-fetch needed
```

**Fix:** Move `updateVersion` logic into `bulkUpdateEditedValues` and use jOOQ's `.returning().fetchSingle()` to get updated records in the same DB round-trip.

---

### Anti-Pattern 3: Null-Checking Lists from Generated API Models (LSP Violation)

OpenAPI-generated models with default-empty list fields should never be null. Adding `!= null` checks at call sites implies distrust of the contract and pollutes code with unnecessary guards.

```java
// ❌ BAD — unnecessary null guard on API-generated list
if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {

// ❌ BAD — unnecessary null guard on validated field list
if (fields == null || fields.isEmpty()) {

// ✅ GOOD — trust the OpenAPI-generated model contract
if (!request.getDocumentIds().isEmpty()) {

// ✅ GOOD — clean early return
if (fields.isEmpty()) {
```

**Fix:** Remove null checks on list fields that are guaranteed non-null by the OpenAPI specification (using `x-field-extra-annotation` or default values in the model). If the field CAN be null (e.g., caller passes null from a test), handle that at the boundary (service layer) rather than in the adapter/validator.
