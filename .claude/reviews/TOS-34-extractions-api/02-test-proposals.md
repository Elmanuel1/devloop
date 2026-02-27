# Test Case Proposals — TOS-34 Extractions API (REVISED)

Revision date: 2026-02-25

Changes from original version:
- TC-A-R07: added "all document IDs must belong to the same tender"
- TC-S-C03 / resolver tests: documentIds default to empty list, never null
- TC-A-FN05: verify ALL TenderFieldName enum values are captured
- Cancel extraction: 202 (not 204); already-cancelled returns error (not silent no-op)
- Entity not found cases added to createExtraction, listExtractionFields, bulkUpdateFields

---

## Unit Tests — ExtractionServiceImplSpec

### createExtraction (9 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-S-C01 | Happy path with explicit document IDs and field names | ExtractionResult with status PENDING, version=0 |
| TC-S-C02 | Happy path with null fields (no field filter) | Extraction created, fieldNamesJsonb is null |
| TC-S-C03 | Happy path with auto-resolved documents (empty documentIds list) | Extraction created with adapter-resolved IDs; documentIds defaults to empty list not null |
| TC-S-C04 | Unsupported entity type | BadRequestException `api.extraction.entityTypeNotSupported` |
| TC-S-C05 | verifyOwnership fails (wrong company) | NotFoundException propagates, insert never called |
| TC-S-C06 | resolveDocumentIds fails (no ready docs) | BadRequestException `api.extraction.noReadyDocuments` propagates |
| TC-S-C07 | validateFieldNames fails (invalid name) | BadRequestException `api.extraction.invalidField` propagates |
| TC-S-C08 | repository.insert throws DB error | RuntimeException propagates |
| TC-S-C09 | Tender in won/lost/cancelled status | BadRequestException `api.extraction.tenderNotActive` from adapter |

### listExtractions (10 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-S-L01 | Happy path — no more pages | 5 items, null cursor |
| TC-S-L02 | Has more pages — cursor set | 5 items, non-null cursor encoding last record |
| TC-S-L03 | Empty result set | Empty data, null cursor |
| TC-S-L04 | Status filter passed to repository | ExtractionQuery.status = "processing" |
| TC-S-L05 | Null status — no filter | ExtractionQuery.status = null |
| TC-S-L06 | Null limit defaults to 20 | ExtractionQuery.limit = 20 |
| TC-S-L07 | Limit > 100 resets to 20 | ExtractionQuery.limit = 20 |
| TC-S-L08 | Limit < 1 resets to 20 | ExtractionQuery.limit = 20 |
| TC-S-L09 | Valid cursor parsed and passed | Non-null cursorCreatedAt + cursorId in query |
| TC-S-L10 | Wrong company — verifyOwnership fails | NotFoundException, repository never called |

### getExtraction (5 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-S-G01 | Happy path — correct version | ExtractionResult with all fields, version=2 |
| TC-S-G02 | Not found (DB row missing) | NotFoundException |
| TC-S-G03 | Cross-tenant — different company | NotFoundException `api.extraction.notFound` |
| TC-S-G04 | Null errors JSONB | dto.errors = [] (empty list, not null) |
| TC-S-G05 | Malformed errors JSONB | dto.errors = [], logs warning, no exception |

### cancelExtraction (8 cases)

Note: cancelExtraction returns 202 (accepted, async) not 204. Already-cancelled is NOT
a silent no-op — it returns an error indicating invalid state.

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-S-X01 | Cancel PENDING extraction | updateStatus + deleteByExtractionId called |
| TC-S-X02 | Cancel PROCESSING extraction | Same as X01 |
| TC-S-X03 | Already CANCELLED — returns error | BadRequestException `api.extraction.cannotCancel` (invalid state, not no-op) |
| TC-S-X04 | Cancel COMPLETED — blocked | BadRequestException `api.extraction.cannotCancel` |
| TC-S-X05 | Cancel FAILED — blocked | BadRequestException `api.extraction.cannotCancel` |
| TC-S-X06 | Not found | NotFoundException propagates |
| TC-S-X07 | Cross-tenant | NotFoundException `api.extraction.notFound` |
| TC-S-X08 | Entity (tender) not found | NotFoundException propagates from adapter verifyOwnership |

### listExtractionFields (8 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-S-F01 | Happy path with entity context | Fields mapped with correct entityType + entityId |
| TC-S-F02 | Has more pages — cursor set | data.size == limit, cursor non-null |
| TC-S-F03 | fieldName filter passed to query | ExtractionFieldQuery.fieldName = "closing_date" |
| TC-S-F04 | documentId filter passed to query | ExtractionFieldQuery.documentId = UUID string |
| TC-S-F05 | Null documentId — no filter | ExtractionFieldQuery.documentId = null |
| TC-S-F06 | Extraction not found | NotFoundException, fieldRepo never called |
| TC-S-F07 | Empty field list | Empty data, null cursor |
| TC-S-F08 | Entity (tender) not found | NotFoundException propagates |

### bulkUpdateFields (10 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-S-B01 | Happy path — If-Match matches, fields updated | Response with updated DTOs in request order |
| TC-S-B02 | Missing If-Match header (null) | IfMatchRequiredException |
| TC-S-B03 | Blank If-Match header | IfMatchRequiredException |
| TC-S-B04 | Stale ETag | StaleVersionException |
| TC-S-B05 | Field not owned by extraction | BadRequestException |
| TC-S-B06 | Extraction not found | NotFoundException propagates |
| TC-S-B07 | Cross-tenant | NotFoundException |
| TC-S-B08 | fieldIds extracted correctly from request | Correct UUID strings passed to validation |
| TC-S-B09 | Response preserves request order | [B, A, C] request → [B, A, C] response |
| TC-S-B10 | Entity (tender) not found | NotFoundException propagates |

---

## Unit Tests — TenderExtractionAdapterSpec

### entityType (1 case)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-A-ET01 | Returns EntityType.TENDER | TENDER |

### verifyOwnership (5 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-A-V01 | Tender belongs to company, status=pending | No exception |
| TC-A-V02 | Tender belongs to company, status=submitted | No exception |
| TC-A-V03 | Tender not found | NotFoundException propagates |
| TC-A-V04 | Tender belongs to different company | NotFoundException `api.tender.notFound` |
| TC-A-V05 | Tender in won/lost/cancelled status | BadRequestException `api.extraction.tenderNotActive` |

### resolveDocumentIds — explicit (8 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-A-R01 | All docs valid and owned | List of 2 ID strings |
| TC-A-R02 | One doc not found | NotFoundException `api.document.notFound` |
| TC-A-R03 | Doc belongs to different tender | BadRequestException `api.extraction.documentNotOwned` |
| TC-A-R04 | Doc status is "processing" (not ready) | BadRequestException `api.extraction.documentNotReady` |
| TC-A-R05 | Doc status is "failed" | BadRequestException `api.extraction.documentNotReady` |
| TC-A-R06 | Empty explicit list — falls to auto-resolve | Auto-resolve path executed |
| TC-A-R07 | Doc IDs from two different tenders | BadRequestException `api.extraction.documentNotOwned` for second tender's doc |
| TC-A-R08 | Null documentIds — falls to auto-resolve | Auto-resolve path executed (never null, always defaults to empty) |

TC-A-R07 tests that "all document IDs must belong to the same tender" is enforced.
Each doc's `tenderId` must equal `entityId`.

### resolveDocumentIds — auto-resolve (3 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-A-A01 | Auto-resolve returns 3 ready docs | List of 3 IDs |
| TC-A-A02 | No ready docs available | BadRequestException `api.extraction.noReadyDocuments` |
| TC-A-A03 | Correct params passed to findByTenderId | ("tender-abc", "ready", 200, null, null) |

### validateFieldNames (5 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-A-FN01 | All valid field names | Same list returned |
| TC-A-FN02 | One invalid field name | BadRequestException `api.extraction.invalidField` |
| TC-A-FN03 | Null fields | Returns null |
| TC-A-FN04 | Empty fields list | Returns null |
| TC-A-FN05 | All 17 TenderFieldName enum values accepted (name, reference_number, location, scope_of_work, delivery_method, currency, closing_date, events, start_date, completion_date, inquiry_deadline, submission_method, submission_url, bonds, conditions, parties, liquidated_damages) | No exception for any of them |

TC-A-FN05 must exhaustively test every value in the `TenderFieldName` enum. If the enum
gains new values, this test must fail until `validateFieldNames` is updated.

---

## Unit Tests — ExtractionPipelineTriggerServiceSpec (new — PR #2)

### trigger (5 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-T-TG01 | Happy path — all docs have S3 metadata | MessagePublisher.publish called with correctly populated ExtractionPipelineTriggerMessage |
| TC-T-TG02 | requestedFields null — passes null in message | requestedFields = null in published message |
| TC-T-TG03 | requestedFields non-empty — passes correctly | requestedFields list preserved |
| TC-T-TG04 | documentIds empty list | MessagePublisher.publish called with empty documents list |
| TC-T-TG05 | tenderDocumentRepository.findByIds returns fewer docs than IDs | Published message has only the found docs (no exception — upstream validated) |

---

## Unit Tests — ExtractionPipelineHandlerSpec (new — PR #2)

### handle (5 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-H-HD01 | Valid message with all fields | pipelineService.run called with correct ExtractionPipelineContext |
| TC-H-HD02 | Missing extractionId in message | pipelineService.run never called; warning logged |
| TC-H-HD03 | Empty documents list | pipelineService.run never called; warning logged |
| TC-H-HD04 | requestedFields null in message | context.requestedFields = null passed to run |
| TC-H-HD05 | JSON deserialization failure | Exception propagates (SQS retries) |

---

## Unit Tests — ExtractionPipelineServiceImplSpec (new — PR #3)

### run (10 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-P-RN01 | Extraction already processing (status guard returns 0) | Early return, no dispatch |
| TC-P-RN02 | All docs relevant and dispatched successfully | pipeline_state all=started, updateCompleted NOT called yet (waits for webhooks) |
| TC-P-RN03 | One doc skipped, one dispatched | pipeline_state: 1 skipped + 1 started |
| TC-P-RN04 | All docs skipped | pipeline_state all=skipped, updateFailed called immediately |
| TC-P-RN05 | All docs fail dispatch | pipeline_state all=failed, updateFailed called immediately |
| TC-P-RN06 | Partial failure — one doc fails, one succeeds | pipeline_state: 1 failed + 1 started; extraction stays processing |
| TC-P-RN07 | requestedFields null — no docs skipped by filter | All docs dispatched (no filter applies) |
| TC-P-RN08 | S3 download fails for one doc | That doc marked failed; other docs continue |
| TC-P-RN09 | Dispatcher throws for one doc | That doc marked failed; other docs continue |
| TC-P-RN10 | Parallelism — two docs processed concurrently | Both futures complete; pipelineExecutor called twice |

### processDocument (3 cases — private method via observable side effects)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-P-PD01 | Classifier returns "rfp", requested field needs "rfp" doc type | Doc is relevant, dispatcher called |
| TC-P-PD02 | Classifier returns "drawing", no requested field maps to drawing | Doc is skipped |
| TC-P-PD03 | Classifier returns "unknown" | Doc treated as relevant (conservative), dispatcher called |

---

## Unit Tests — PreconExtractionWebhookServiceImplSpec (new — PR #4)

### processWebhook (12 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-W-PW01 | Reducto status=succeeded, extraction pending → processes and transitions | Fields bulk-inserted, pipeline_state updated, updateCompleted called |
| TC-W-PW02 | Reducto status=failed → marks doc failed in pipeline_state | updateFailed called if all docs terminal |
| TC-W-PW03 | Reducto status=running (non-final) → no-op | No state changes |
| TC-W-PW04 | Extraction already completed → no-op | No writes to DB |
| TC-W-PW05 | Extraction already failed → no-op | No writes to DB |
| TC-W-PW06 | Missing documentId in metadata → logged, skipped | No writes |
| TC-W-PW07 | Missing extractionId in metadata → logged, skipped | No writes |
| TC-W-PW08 | Extraction not found → logged, no exception bubbled | No writes |
| TC-W-PW09 | Partial completion — last doc completes | updateCompleted called |
| TC-W-PW10 | Partial failure — all remaining docs failed | updateFailed called |
| TC-W-PW11 | Mix of completed + skipped docs — at least one completed | updateCompleted called (not updateFailed) |
| TC-W-PW12 | Duplicate webhook (same jobId arrives twice) | Second call is no-op (pipeline_state entry already terminal) |

---

## Integration Tests — ExtractionRepositoryImplSpec (existing + new cases)

### insert (3 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-R-I01 | Insert with all fields | Record fully persisted, version=0, createdAt non-null |
| TC-R-I02 | Insert with null fieldNames | null persisted for optional column |
| TC-R-I03 | Insert returns new record (RETURNING) | Same ID returned |

### findById (4 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-R-FB01 | Find existing | Record matches inserted data |
| TC-R-FB02 | Non-existent ID | NotFoundException `api.extraction.notFound` |
| TC-R-FB03 | Soft-deleted extraction | NotFoundException (deleted_at filter) |
| TC-R-FB04 | One deleted, one live — only live visible | findById succeeds for live, throws for deleted |

### findByEntityId (9 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-R-FE01 | Only returns matching company + entity | 1 result |
| TC-R-FE02 | Excludes soft-deleted | 1 result |
| TC-R-FE03 | Status filter — only PENDING | 1 result with status=pending |
| TC-R-FE04 | Null status — all statuses | All results |
| TC-R-FE05 | Returns limit+1 for has_more | 4 records for limit=3 |
| TC-R-FE06 | Empty when no extractions | Empty list |
| TC-R-FE07 | Ordered by created_at DESC, id DESC | Newest first |
| TC-R-FE08 | Cursor pagination — second page | Correct keyset |
| TC-R-FE09 | Cursor past last record | Empty list |

### updateStatus (3 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-R-US01 | Successfully updates | 1 row, status changed |
| TC-R-US02 | Non-existent ID | Returns 0 |
| TC-R-US03 | Soft-deleted — no update | Returns 0 |

### incrementVersion (5 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-R-IV01 | Version matches — increments | version 0 → 1 |
| TC-R-IV02 | Version mismatch — optimistic lock | Returns 0, version unchanged |
| TC-R-IV03 | Non-existent ID | Returns 0 |
| TC-R-IV04 | Soft-deleted — no increment | Returns 0 |
| TC-R-IV05 | Sequential increments | 0 → 1 → 2 correctly |

### softDelete (3 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-R-SD01 | Soft-deletes existing | deleted_at set |
| TC-R-SD02 | Already soft-deleted — returns 0 | Idempotent |
| TC-R-SD03 | Non-existent ID | Returns 0 |

### existsProcessingExtractionForDocument (3 cases — new in PR #1)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-R-EP01 | Extraction with status=processing references this doc | Returns true |
| TC-R-EP02 | Extraction exists but status=completed | Returns false |
| TC-R-EP03 | No extractions reference this documentId | Returns false |

---

## Integration Tests — ExtractionPipelineRepositoryImplSpec (new — PR #1)

### updateProcessingStarted (3 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-PR-PS01 | Extraction status=pending — transitions to processing | 1 row, status=processing, started_at set |
| TC-PR-PS02 | Extraction already processing — idempotency guard | Returns 0, no update |
| TC-PR-PS03 | Non-existent ID | Returns 0 |

### updateCompleted (2 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-PR-UC01 | Updates status + completed_at | 1 row, status=completed, completed_at non-null |
| TC-PR-UC02 | Non-existent ID | Returns 0 |

### updateFailed (2 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-PR-UF01 | Updates status + errors + completed_at | 1 row, status=failed, errors JSONB set |
| TC-PR-UF02 | Non-existent ID | Returns 0 |

### updatePipelineState (3 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-PR-UP01 | Writes pipeline_state JSONB | Value persisted and readable |
| TC-PR-UP02 | Overwrites previous pipeline_state | New value replaces old |
| TC-PR-UP03 | Non-existent ID | Returns 0 |

---

## Integration Tests — ExtractionFieldRepositoryImplSpec (existing + bulkInsert)

### findByExtractionId (7 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-RF-FE01 | Returns all fields for extraction | 3 results scoped correctly |
| TC-RF-FE02 | fieldName filter | 1 result |
| TC-RF-FE03 | documentId filter via JSONB containment | Only matching fields |
| TC-RF-FE04 | Returns limit+1 for has_more | 3 for limit=2 |
| TC-RF-FE05 | Cursor pagination | Correct page |
| TC-RF-FE06 | Empty when no fields | Empty list |
| TC-RF-FE07 | Ordered by created_at DESC, id DESC | Newest first |

### findById (2 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-RF-FB01 | Finds field by ID | Record matches |
| TC-RF-FB02 | Non-existent ID | NotFoundException |

### findAllByIds (4 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-RF-FA01 | Returns all for given IDs | 2 results |
| TC-RF-FA02 | Empty ID list | Empty list |
| TC-RF-FA03 | Null ID list | Empty list |
| TC-RF-FA04 | Partial match — some IDs missing | Returns only found records |

### updateEditedValue (2 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-RF-UV01 | Updates value + updatedAt | 1 row, values changed |
| TC-RF-UV02 | Non-existent ID | Returns 0 |

### deleteByExtractionId (3 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-RF-DB01 | Deletes all fields for extraction | 3 deleted, other extraction untouched |
| TC-RF-DB02 | No fields to delete | Returns 0 |
| TC-RF-DB03 | ON DELETE CASCADE from parent extraction | Fields gone when parent deleted |

### bulkInsert (4 cases — new in PR #1)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-RF-BI01 | Insert 3 records | All 3 persisted, count=3 |
| TC-RF-BI02 | Empty list | Returns 0, no insert |
| TC-RF-BI03 | Duplicate insert — ON CONFLICT DO NOTHING | Second insert returns 0, no error |
| TC-RF-BI04 | FK violation (extraction not exists) | DataIntegrityViolationException |

---

## Integration Tests — ExtractionControllerSpec (revised)

### POST /v1/extractions (12 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-C-CR01 | Happy path | 201, Location header, ETag "v0" |
| TC-C-CR02 | With explicit documentIds | 201 |
| TC-C-CR03 | With explicit field names | 201 |
| TC-C-CR04 | Tender not found | 404 `api.tender.notFound` |
| TC-C-CR05 | Tender belongs to different company | 404 |
| TC-C-CR06 | No ready documents | 400 `api.extraction.noReadyDocuments` |
| TC-C-CR07 | Document not ready (processing) | 400 `api.extraction.documentNotReady` |
| TC-C-CR08 | Document belongs to different tender | 400 `api.extraction.documentNotOwned` |
| TC-C-CR09 | Invalid field name | 400 `api.extraction.invalidField` |
| TC-C-CR10 | Missing X-Context-Id | 400 |
| TC-C-CR11 | Non-numeric X-Context-Id | 403 |
| TC-C-CR12 | Tender in won/lost/cancelled status | 400 `api.extraction.tenderNotActive` |

### GET /v1/extractions (5 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-C-LE01 | Paginated list | 200 with data + cursor |
| TC-C-LE02 | Empty list | 200 with empty data |
| TC-C-LE03 | Status filter | 200, only matching status |
| TC-C-LE04 | Wrong company | 404 |
| TC-C-LE05 | All fit on one page | 200, null cursor |

### GET /v1/extractions/{id} (6 cases — unchanged)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-C-GE01 | Happy path with ETag | 200, ETag "v0", full body |
| TC-C-GE02 | If-None-Match matches — 304 | 304, no body |
| TC-C-GE03 | If-None-Match stale | 200 with updated ETag |
| TC-C-GE04 | Not found | 404 |
| TC-C-GE05 | Cross-tenant | 404 |
| TC-C-GE06 | Soft-deleted | 404 |

### DELETE /v1/extractions/{id} — now returns 202 (8 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-C-XE01 | Cancel PENDING | 202, status=cancelled, fields deleted |
| TC-C-XE02 | Cancel PROCESSING | 202 |
| TC-C-XE03 | Already CANCELLED — returns error | 400 `api.extraction.cannotCancel` (NOT a 202 no-op) |
| TC-C-XE04 | COMPLETED — blocked | 400 `api.extraction.cannotCancel` |
| TC-C-XE05 | FAILED — blocked | 400 |
| TC-C-XE06 | Not found | 404 |
| TC-C-XE07 | Cross-tenant | 404 |
| TC-C-XE08 | Entity (tender) not found | 404 |

### GET /v1/extractions/{id}/fields (8 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-C-LF01 | Happy path | 200 with fields |
| TC-C-LF02 | fieldName filter | 200, filtered results |
| TC-C-LF03 | documentId filter | 200, filtered results |
| TC-C-LF04 | Pagination cursor | 200, cursor non-null |
| TC-C-LF05 | Empty fields | 200, empty data |
| TC-C-LF06 | Extraction not found | 404 |
| TC-C-LF07 | Cross-tenant | 404 |
| TC-C-LF08 | Entity (tender) not found | 404 |

### PATCH /v1/extractions/{id}/fields (9 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-C-BU01 | Happy path — If-Match matches | 200 with updated fields |
| TC-C-BU02 | Missing If-Match | 400 `api.validation.ifMatchRequired` |
| TC-C-BU03 | Stale If-Match | 412 `api.extraction.staleVersion` |
| TC-C-BU04 | Field not owned by extraction | 400 |
| TC-C-BU05 | Not found | 404 |
| TC-C-BU06 | Cross-tenant | 404 |
| TC-C-BU07 | Version incremented after success | GET returns ETag "v1" |
| TC-C-BU08 | Concurrent update — second fails | First: 200, Second: 412 |
| TC-C-BU09 | Entity (tender) not found | 404 |

### POST /webhooks/reducto/precon (new — PR #4, 6 cases)

| ID | Test Case | Expected |
|----|-----------|----------|
| TC-C-WH01 | Valid Svix signature, succeeded payload | 202, fields inserted |
| TC-C-WH02 | Invalid Svix signature | 401 |
| TC-C-WH03 | Missing svix-id header | 401 |
| TC-C-WH04 | Failed Reducto status | 202, extraction marked failed (if all terminal) |
| TC-C-WH05 | Non-final Reducto status (e.g. running) | 202, no state change |
| TC-C-WH06 | Duplicate webhook (extraction already completed) | 202, no state change (idempotent) |

---

## Notes

- `bulkUpdateFields` helpers (`validateFieldsOwnedByExtraction`, `applyFieldUpdates`, `refetchFieldsInOrder`) were created by the code-writer in violation #3 fix. Verify they exist before writing TC-S-B cases.
- TC-S-X03 change: already-cancelled is now an error (400 `api.extraction.cannotCancel`), not a silent no-op. This matches the user decision: cancelled is a final state with no further transitions allowed.
- TC-C-XE01/XE02: response code is 202 (async), not 204. Update `ExtractionController` accordingly.
- All "entity not found" cases (TC-S-C09, TC-S-F08, TC-S-B10, TC-C-CR12, TC-C-XE08, TC-C-LF08, TC-C-BU09) require `TenderExtractionAdapter.verifyOwnership` to check tender status, added in PR #1.
- TC-A-FN05 must iterate `TenderFieldName.values()` programmatically so the test stays exhaustive as the enum grows.

---

## Total Test Count (revised)

| Category | Count |
|----------|-------|
| ExtractionServiceImplSpec | 42 |
| TenderExtractionAdapterSpec | 21 |
| ExtractionPipelineTriggerServiceSpec | 5 (new) |
| ExtractionPipelineHandlerSpec | 5 (new) |
| ExtractionPipelineServiceImplSpec | 13 (new) |
| PreconExtractionWebhookServiceImplSpec | 12 (new) |
| ExtractionRepositoryImplSpec | 32 (29 + 3 new) |
| ExtractionPipelineRepositoryImplSpec | 10 (new) |
| ExtractionFieldRepositoryImplSpec | 22 (18 + 4 new) |
| ExtractionControllerSpec | 54 (46 + 8 revised/new) |
| **Grand total** | **216** |
