# Reviewer Report — TOS-34 Extractions API

- **Files reviewed:** 21
- **Total violations:** 10
- **Verdict:** FAIL
- **Skipped by user:** #2 (raw DSL.field — blocked on jOOQ regen), #5 (hardcoded "processing" — intentional)

---

## Violations

### #1 — ExtractionServiceImpl.java (lines 74, 94) — OCP

**Rule:** Open/Closed Principle
**Violation:** `resolveAdapter(EntityType.TENDER)` is hardcoded in both `createExtraction` and `listExtractions`. Adding a second entity type requires editing both methods.
**Fix:** Replace with `resolveAdapter(request.getEntityType())` in `createExtraction`. Add `entityType` parameter to `listExtractions`.

---

### #2 — SKIPPED (raw DSL.field access — blocked on jOOQ regen after V3.5)

---

### #3 — ExtractionServiceImpl.java (lines 210-279) — SRP

**Rule:** Single Responsibility / Method length
**Violation:** `bulkUpdateFields` is 69 lines with 9 distinct responsibilities: If-Match validation, ETag parsing, ownership check, field existence validation, cross-extraction membership validation, field update loop, optimistic lock increment, re-fetch and reorder, DTO mapping.
**Fix:** Extract:
- `validateFieldsOwnedByExtraction(fieldIds, extractionId)` — lines ~222-247
- `applyFieldUpdates(request, extractionId, expectedVersion)` — lines ~249-260
- `refetchFieldsInOrder(fieldIds, extraction)` — lines ~262-274

---

### #4 — ExtractionServiceImpl.java (lines 54, 61-66) — Testability

**Rule:** SRP / Testability — no @PostConstruct for state initialization
**Violation:** `adapterMap` is a `new HashMap<>()` built via `@PostConstruct`. Cannot be tested without running the full Spring lifecycle.
**Fix:** Drop `@RequiredArgsConstructor`. Write a manual constructor that takes all deps AND builds the map:
```java
public ExtractionServiceImpl(ExtractionRepository extractionRepository,
                              ExtractionFieldRepository extractionFieldRepository,
                              ExtractionMapper extractionMapper,
                              ExtractionFieldMapper extractionFieldMapper,
                              ObjectMapper objectMapper,
                              List<EntityExtractionAdapter> adapters) {
    this.extractionRepository = extractionRepository;
    // ... other assignments ...
    this.adapters = adapters;
    this.adapterMap = adapters.stream()
            .collect(Collectors.toMap(EntityExtractionAdapter::entityType, a -> a));
}
```

---

### #5 — SKIPPED (hardcoded "processing" in TenderDocumentRepositoryImpl — intentional)

---

### #6 — TenderDocumentRepositoryImpl.java (line 115) — Hardcoded string

**Rule:** Pattern 15 / Hard Rule — no hardcoded domain values
**Violation:** `.set(TENDER_DOCUMENTS.STATUS, "ready")`
**Fix:** Use `TenderDocumentStatus.READY.getValue()`

---

### #7 — TenderDocumentRepositoryImpl.java (line 127) — Hardcoded string

**Rule:** Pattern 15 / Hard Rule — no hardcoded domain values
**Violation:** `.set(TENDER_DOCUMENTS.STATUS, "failed")`
**Fix:** Use `TenderDocumentStatus.FAILED.getValue()`

---

### #8 — TenderDocumentRepository.java (line 13) — Pattern 4

**Rule:** Pattern 4 — Repository `findById` returns record directly, throws internally
**Violation:** `Optional<TenderDocumentsRecord> findById(String id)` returns Optional. Forces `.orElseThrow()` at two call-sites in `TenderDocumentServiceImpl`.
**Fix:**
1. Change interface: `TenderDocumentsRecord findById(String id)`
2. Move `NotFoundException` throw into `TenderDocumentRepositoryImpl`
3. Remove `.orElseThrow()` calls in `TenderDocumentServiceImpl`

---

### #9 — TenderExtractionAdapter.java (lines 70, 81) — Hardcoded string

**Rule:** Pattern 15 / Hard Rule — no hardcoded domain values
**Violation:** `!"ready".equals(doc.getStatus())` and `findByTenderId(entityId, "ready", 200, null, null)`
**Fix:** Replace `"ready"` with `TenderDocumentStatus.READY.getValue()`. Import `com.tosspaper.precon.generated.model.TenderDocumentStatus`.

---

### #10 — ExtractionMapper.java + ExtractionFieldMapper.java (lines 22/23) — DIP

**Rule:** DIP / Testability — no static concrete instantiation
**Violation:** Both mapper interfaces declare `ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());` as a static constant. Bypasses Spring's configured `ObjectMapper` bean.
**Fix:** Create an injectable `@Component` class `ExtractionJsonConverter` that receives the Spring-managed `ObjectMapper` via constructor injection. Provide the JSONB conversion methods there. Reference from both mappers via `@Mapper(componentModel = "spring", uses = {ExtractionJsonConverter.class})`.

---

## Clean Files (15)

- `ApiErrorMessages.java`
- `ExtractionRepository.java`
- `ExtractionRepositoryImpl.java`
- `ExtractionFieldRepository.java`
- `ExtractionFieldRepositoryImpl.java`
- `ExtractionController.java`
- `ExtractionService.java`
- `ExtractionApplicationService.java`
- `ExtractionApplicationServiceStub.java`
- `EntityExtractionAdapter.java`
- `ExtractionQuery.java`
- `ExtractionFieldQuery.java`
- `ExtractionResult.java`
- `NotImplementedException.java`
- `V3.5__add_timing_and_errors_to_extractions.sql`
