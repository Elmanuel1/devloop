---
name: refactoring-checklist
description: Practical audit checklist for code review and refactoring. Covers KISS, coupling, exception handling, and a quick-reference table of common problems and fixes. Use as the final review pass.
allowed-tools:
  - Read
  - Glob
  - Grep
---

# Refactoring Checklist

Final audit pass — use after reviewing against SOLID, DRY, and testability individually.

## When to Use This Skill

- Reviewer: final sweep before approving or requesting changes
- Code-writer: self-check before opening a PR
- NOT for: upfront architecture design

---

## KISS — Keep It Simple

> The simplest solution that works is the best solution.

Flag when code is more complex than the problem requires:

- [ ] Abstraction with one implementation and no plan for a second?
- [ ] Wrapper/delegate that adds nothing (just passes through)?
- [ ] Pattern (Strategy, Factory, Builder) used for 2 or fewer cases?
- [ ] Helper/utility class used in exactly one place?
- [ ] Configuration for values that never change?
- [ ] Generic solution when the requirement is specific?
- [ ] Interface extracted for a class that will never be swapped?

**Examples:**

```java
// BAD — Strategy pattern for 2 fixed cases
public interface ExportStrategy { void export(Data d); }
public class CsvExportStrategy implements ExportStrategy { ... }
public class PdfExportStrategy implements ExportStrategy { ... }
public class ExportService {
    private final Map<Format, ExportStrategy> strategies; // overkill
}

// GOOD — simple conditional when cases are fixed and few
public class ExportService {
    public void export(Data d, Format format) {
        if (format == CSV) exportCsv(d);
        else exportPdf(d);
    }
}
```

```java
// BAD — wrapper that does nothing
public class OrderServiceWrapper {
    private final OrderService delegate;
    public Order create(OrderRequest r) { return delegate.create(r); }
    public Order get(Long id) { return delegate.get(id); }
}

// GOOD — just use OrderService directly
```

```java
// BAD — Builder for 3 fields
Notification.builder().title(t).body(b).recipient(r).build();

// GOOD — constructor or static factory
new Notification(t, b, r);
```

---

## Duplication (DRY)

- [ ] Same logic in multiple methods/classes?
- [ ] Similar classes that could be merged?
- [ ] Copy-pasted code with minor variations?

_For detailed patterns, see `.claude/skills/dry-patterns/SKILL.md`._

## Single Responsibility

- [ ] Class doing more than one thing?
- [ ] Method longer than 20 lines?
- [ ] More than 5 dependencies?

## Testability

- [ ] Using constructor injection?
- [ ] Avoiding static methods for business logic?
- [ ] Dependencies are interfaces, not concrete classes?
- [ ] Complex conditionals extracted to methods?

_For detailed patterns, see `.claude/skills/testability/SKILL.md`._

## Coupling

- [ ] Class knows too much about other classes?
- [ ] Changing one class requires changing many others?
- [ ] Circular dependencies?

---

## Suggested Refactorings

| Problem | Refactoring | Benefit |
|---------|-------------|---------|
| Large class | Extract Class | SRP, testability |
| Long method | Extract Method | Readability, testability |
| Duplicate code | Extract to shared service | DRY |
| Complex conditional | Replace with Strategy | OCP, testability |
| Field injection | Convert to constructor injection | DIP, testability |
| Static method | Convert to injectable service | Testability |
| God class | Split by responsibility | SRP, maintainability |
| Similar classes | Extract common base/interface | DRY |
| Hardcoded values | Extract to configuration | Flexibility |
| Direct instantiation | Use factory or injection | Testability |
| Unnecessary abstraction | Inline / remove layer | KISS |
| Passthrough wrapper | Remove, use delegate directly | KISS |
| Over-configured | Hardcode the constant | KISS |

---

## Exception Handling

**Only catch exceptions when you want to change behavior.**

### When to Catch

| Reason | Example |
|--------|---------|
| Transform exception type | `catch (IOException e) { throw new StorageException(e); }` |
| Handle and continue | `catch (TimeoutException e) { return fallbackValue; }` |
| Add context | `catch (SQLException e) { throw new DataAccessException("user " + id, e); }` |
| Log and suppress | `catch (NotificationException e) { log.warn("notification failed"); }` |

### When NOT to Catch

```java
// BAD — useless catch-rethrow
try {
    doSomething();
} catch (BadRequestException e) {
    throw e;  // pointless! just remove the try-catch
}

// BAD — wrapping without adding value
try {
    process(data);
} catch (Exception e) {
    throw new RuntimeException(e);  // no context added, just noise
}

// GOOD — let exceptions propagate naturally
doSomething();
```

### Valid Exception Handling

```java
// Transform to domain exception
try {
    fileService.save(content);
} catch (IOException e) {
    throw new StorageException("Failed to save document: " + documentId, e);
}

// Handle and provide fallback
try {
    return cache.get(key);
} catch (CacheException e) {
    log.debug("Cache miss, fetching from source");
    return fetchFromSource(key);
}

// Partial failure handling (continue processing others)
for (Document doc : documents) {
    try {
        processDocument(doc);
    } catch (ProcessingException e) {
        log.warn("Skipping document {}: {}", doc.getId(), e.getMessage());
        failedDocs.add(doc);
    }
}
```
