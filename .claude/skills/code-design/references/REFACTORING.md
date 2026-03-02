# Refactoring Checklist

Use when analyzing code for improvements.

---

## Duplication (DRY)

- [ ] Same logic in multiple methods/classes?
- [ ] Similar classes that could be merged?
- [ ] Copy-pasted code with minor variations?

## Single Responsibility

- [ ] Class doing more than one thing?
- [ ] Method longer than 20 lines?
- [ ] More than 5 dependencies?

## Testability

- [ ] Using constructor injection?
- [ ] Avoiding static methods for business logic?
- [ ] Dependencies are interfaces, not concrete classes?
- [ ] Complex conditionals extracted to methods?

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
// ❌ BAD — useless catch-rethrow
try {
    doSomething();
} catch (BadRequestException e) {
    throw e;  // pointless! just remove the try-catch
}

// ❌ BAD — wrapping without adding value
try {
    process(data);
} catch (Exception e) {
    throw new RuntimeException(e);  // no context added, just noise
}

// ✅ GOOD — let exceptions propagate naturally
doSomething();  // exceptions will propagate up the stack
```

### Valid Exception Handling

```java
// ✅ Transform to domain exception
try {
    fileService.save(content);
} catch (IOException e) {
    throw new StorageException("Failed to save document: " + documentId, e);
}

// ✅ Handle and provide fallback
try {
    return cache.get(key);
} catch (CacheException e) {
    log.debug("Cache miss, fetching from source");
    return fetchFromSource(key);
}

// ✅ Partial failure handling (continue processing others)
for (Document doc : documents) {
    try {
        processDocument(doc);
    } catch (ProcessingException e) {
        log.warn("Skipping document {}: {}", doc.getId(), e.getMessage());
        failedDocs.add(doc);
    }
}
```

