# Code Writer Status — Violation Fixes

**Status:** IN PROGRESS (132+ turns, still running)

## Violations being fixed

| # | File | Fix | Status |
|---|------|-----|--------|
| 1 | ExtractionServiceImpl | `EntityType.TENDER` → `request.getEntityType()` | In progress |
| 3 | ExtractionServiceImpl | Extract `bulkUpdateFields` into 3 private helpers | In progress |
| 4 | ExtractionServiceImpl | `@PostConstruct` → constructor-time map init | In progress |
| 6 | TenderDocumentRepositoryImpl | `"ready"` → `TenderDocumentStatus.READY.getValue()` | In progress |
| 7 | TenderDocumentRepositoryImpl | `"failed"` → `TenderDocumentStatus.FAILED.getValue()` | In progress |
| 8 | TenderDocumentRepository | `Optional<T> findById` → `T findById` (throws internally) | In progress |
| 9 | TenderExtractionAdapter | `"ready"` → `TenderDocumentStatus.READY.getValue()` | In progress |
| 10 | ExtractionMapper + ExtractionFieldMapper | Static ObjectMapper → injectable `ExtractionJsonConverter` | In progress |

## Skipped violations (intentional)

| # | File | Reason |
|---|------|--------|
| 2 | ExtractionServiceImpl | Raw `DSL.field("started_at")` — blocked on jOOQ regen after V3.5 |
| 5 | TenderDocumentRepositoryImpl | Hardcoded `"processing"` — intentional |

## Verification

Code-writer will run `./gradlew :libs:api-tosspaper:compileJava --rerun-tasks` after all fixes.

This file will be updated when the code-writer completes.
