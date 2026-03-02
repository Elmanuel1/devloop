# DRY — Don't Repeat Yourself

Eliminate duplication to reduce bugs and maintenance burden.

---

## Identify Duplication

**Signs:**
- Same validation logic in multiple services
- Copy-pasted code with minor variations
- Near-identical classes (e.g., `CustomerPushWorkflow` vs `VendorPushWorkflow`)

---

## Extract Shared Logic

```java
// ❌ BAD — repeated validation
public class ContactService {
    public Contact create(ContactRequest r) {
        if (r.getName() == null || r.getName().isBlank()) throw new ValidationException("Name required");
        if (r.getEmail() == null || !r.getEmail().contains("@")) throw new ValidationException("Valid email required");
        // ...
    }
}

public class VendorService {
    public Vendor create(VendorRequest r) {
        if (r.getName() == null || r.getName().isBlank()) throw new ValidationException("Name required"); // DUPLICATE
        if (r.getEmail() == null || !r.getEmail().contains("@")) throw new ValidationException("Valid email required"); // DUPLICATE
        // ...
    }
}

// ✅ GOOD — single source of truth
@Component
public class EntityValidator {
    public void validateName(String name) { ... }
    public void validateEmail(String email) { ... }
}

// OR use Jakarta Bean Validation (preferred for API layer)
public class ContactRequest {
    @NotBlank private String name;
    @Email private String email;
}
```

---

## Merge Similar Classes

```java
// ❌ BAD — two near-identical workflows
public class CustomerPushWorkflow {
    public void push(Customer c) { validate(c); map(c); send(c); updateStatus(c); }
}
public class VendorPushWorkflow {
    public void push(Vendor v) { validate(v); map(v); send(v); updateStatus(v); }
}

// ✅ GOOD — generic workflow
public abstract class EntityPushWorkflow<T extends Syncable> {
    public void push(T entity) {
        validate(entity);
        Object ext = mapToExternal(entity);
        sendToQuickBooks(ext);
        updateSyncStatus(entity);
    }
    protected abstract void validate(T entity);
    protected abstract Object mapToExternal(T entity);
    // ...
}
```

---

---

## Pagination Utility Duplication

A common DRY violation is duplicating pagination clamping and truncation logic across service classes.

```java
// ❌ BAD — clampLimit duplicated in ExtractionServiceImpl AND ExtractionFieldServiceImpl
private int clampLimit(Integer limit) {
    int effective = limit != null ? limit : 20;
    if (effective < 1 || effective > 100) {
        effective = 20;
    }
    return effective;
}

// Also duplicated:
boolean hasMore = records.size() > effectiveLimit;
if (hasMore) {
    records = records.subList(0, effectiveLimit);
}
```

```java
// ✅ GOOD — extracted to PaginationUtils in com.tosspaper.common
public final class PaginationUtils {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private PaginationUtils() {}

    public static int clampLimit(Integer limit) {
        if (limit == null || limit < MIN_LIMIT || limit > MAX_LIMIT) {
            return DEFAULT_LIMIT;
        }
        return Math.clamp(limit, MIN_LIMIT, MAX_LIMIT);
    }

    public static <T> boolean hasMore(List<T> records, int effectiveLimit) {
        return records.size() > effectiveLimit;
    }

    public static <T> List<T> truncate(List<T> records, int effectiveLimit) {
        return records.size() > effectiveLimit ? records.subList(0, effectiveLimit) : records;
    }
}

// Usage in any service:
int effectiveLimit = PaginationUtils.clampLimit(limit);
boolean hasMore = PaginationUtils.hasMore(records, effectiveLimit);
records = PaginationUtils.truncate(records, effectiveLimit);
```

**Benefit:** Single tested implementation. Behaviour change (e.g., different default) happens in one place.

---

## When NOT to DRY

- Two pieces of code look similar but evolve independently → keep separate
- Premature abstraction creates complexity → wait until pattern repeats 3+ times

