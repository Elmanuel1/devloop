# Architect Plan — Extraction Pipeline (REVISED)

Revision date: 2026-02-25
Revisions address 13 items of Confluence feedback plus 3 open-question decisions.

---

## VIOLATION #8 REVERT ALERT

The code-writer was instructed to change `TenderDocumentRepository.findById` from
`Optional<TenderDocumentsRecord>` to `TenderDocumentsRecord` (throwing internally) as
Reviewer violation #8. The user has since clarified: **this was intentional**. Between
uploading and the S3 event arriving, calls can fail and the Optional was deliberate
defensive programming. The code-writer fix must be checked and potentially reverted.

Action required before merging any code:
- Check whether `TenderDocumentRepository.findById` was already changed to throw internally.
- If changed: revert to `Optional<TenderDocumentsRecord>` on the interface and put `.orElseThrow()` back in `TenderDocumentRepositoryImpl` only for the non-pipeline path, or restore both interface and impl to Optional form.
- The `DocumentUploadProcessor` already handles `NotFoundException` via try/catch — that pattern was working correctly before violation #8 was applied.

---

## Decisions From Open Questions

**Q1 — Tender status gate at extraction creation:**
- `pending` + `submitted` → extraction allowed
- `won` + `lost` + `cancelled` → extraction blocked with `api.extraction.tenderNotActive`
- Tender cancelled while pipeline is running → check Reducto cancel API; if available, cancel outstanding jobs and mark extraction `cancelled`
- Tender won/lost while pipeline is running → block the status transition until extraction completes (i.e., `TenderServiceImpl.updateTender` must check for in-progress extractions)
- Document deleted mid-extraction → block document deletion while extraction is `processing`
- After extraction completes: tender can be cancelled freely; extraction data retained as historical
- Orphaned citations after document deletion → leave as-is; UI shows "source deleted" label

**Q3 — ExtractionFieldsApi split:** Yes, split field endpoints into a separate controller in the next PR (spec-writer task). Not part of this plan.

**Q4 — Next migration version:** V3.6 already exists on the feature branch and on main. V3.7 is the correct next number. Confirmed.

**Q5 — Reducto schema:** Code-writer generates from `TenderFieldName` enum. Schema proposal included in PR #3 section.

---

## Task Breakdown

```
PR #1 — Foundation (shared deps)           <- MUST SHIP FIRST
PR #2 — Pipeline Trigger (SQS)             <- no dependency on #3 or #4
PR #3 — Classification + Reducto Dispatch  <- no dependency on #2 or #4
PR #4 — Field Persistence + Completion     <- no dependency on #2 or #3
```

No two PRs touch the same file. All pipeline files are new. Foundation-only files are
modified only in PR #1.

---

## PR #1 — Foundation

### Migration: `flyway/V3.7__add_pipeline_state_to_extractions.sql`

```sql
-- Stores per-document pipeline state as a typed JSONB array.
-- Each element maps to a DocumentPipelineState Java record:
--   document_id    VARCHAR — the tender document UUID
--   s3_bucket      VARCHAR — S3 bucket where the document lives
--   s3_key         VARCHAR — full S3 object key
--   file_name      VARCHAR — original file name (used for classification)
--   content_type   VARCHAR — MIME type (used for classification)
--   reducto_job_id VARCHAR — Reducto job ID once dispatched (null until dispatched)
--   reducto_file_id VARCHAR — Reducto file ID after upload (null until uploaded)
--   status         VARCHAR — pending | started | completed | failed | skipped
--                            skipped: classification determined doc irrelevant for requested fields
--   error          VARCHAR — error message if status=failed (null otherwise)
ALTER TABLE extractions ADD COLUMN IF NOT EXISTS pipeline_state JSONB;
```

### `ExtractionPipelineRepository` — NEW interface (separate from ExtractionRepository)

This is a dedicated repository for pipeline-specific write operations. It does NOT extend
`ExtractionRepository`. Pipeline state writes are high-frequency and always scoped to the
pipeline subsystem — keeping them separate ensures `ExtractionRepository` stays thin and
its interface is not polluted with pipeline internals.

```java
public interface ExtractionPipelineRepository {

    /**
     * Transitions status from 'pending' to 'processing' and sets started_at.
     * Uses WHERE status='pending' guard — returns 0 if already processing (idempotency).
     */
    int updateProcessingStarted(String id, OffsetDateTime startedAt);

    /**
     * Sets status='completed' and completed_at=now.
     */
    int updateCompleted(String id, OffsetDateTime completedAt);

    /**
     * Sets status='failed', completed_at=now, errors JSONB.
     */
    int updateFailed(String id, OffsetDateTime completedAt, JSONB errors);

    /**
     * Replaces the full pipeline_state JSONB column.
     * Used after each document is dispatched or updated.
     */
    int updatePipelineState(String id, JSONB pipelineState);
}
```

Implementation: `ExtractionPipelineRepositoryImpl`. Uses `DSL.field(...)` for V3.5/V3.7
columns not in the jOOQ codegen (same pattern as `buildExtractionDto` in
`ExtractionServiceImpl`).

### `ExtractionFieldRepository` — new method added

```java
/** Batch-inserts extraction field records. Returns count of inserted rows. */
int bulkInsert(List<ExtractionFieldsRecord> records);
```

### `DocumentPipelineState` — NEW Java record (shared domain object)

Lives in `libs/api-tosspaper/.../precon/DocumentPipelineState.java`.

```java
/**
 * Represents the per-document processing state inside the pipeline_state JSONB column.
 * Each entry in the array is serialized/deserialized from this record.
 * NOT a raw Map — always deserialized to this typed record.
 */
public record DocumentPipelineState(
    String documentId,
    String s3Bucket,
    String s3Key,
    String fileName,
    String contentType,
    String reductoJobId,    // null until dispatched
    String reductoFileId,   // null until uploaded to Reducto
    String status,          // pending | started | completed | failed | skipped
    String error            // null unless status=failed
) {
    public static final String STATUS_PENDING    = "pending";
    public static final String STATUS_STARTED    = "started";
    public static final String STATUS_COMPLETED  = "completed";
    public static final String STATUS_FAILED     = "failed";
    public static final String STATUS_SKIPPED    = "skipped";

    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status)
            || STATUS_FAILED.equals(status)
            || STATUS_SKIPPED.equals(status);
    }
}
```

This record is the authoritative shape for `pipeline_state` array elements. Serialized
to JSON via Jackson in `ExtractionPipelineRepositoryImpl`. Never stored as a raw
`Map<String, Object>`.

### `ApiErrorMessages` — 4 new constants

```java
// Pipeline
public static final String EXTRACTION_PIPELINE_FAILED_CODE        = "api.extraction.pipelineFailed";
public static final String EXTRACTION_PIPELINE_FAILED             = "Extraction pipeline failed: %s";
public static final String EXTRACTION_DOC_DOWNLOAD_FAILED_CODE    = "api.extraction.docDownloadFailed";
public static final String EXTRACTION_DOC_DOWNLOAD_FAILED         = "Failed to download document '%s' from S3.";

// Tender status gate
public static final String EXTRACTION_TENDER_NOT_ACTIVE_CODE = "api.extraction.tenderNotActive";
public static final String EXTRACTION_TENDER_NOT_ACTIVE      = "Cannot create extraction for tender '%s' in '%s' status. Only pending and submitted tenders allow extraction.";

// Document deletion guard
public static final String DOCUMENT_IN_USE_CODE = "api.document.inUse";
public static final String DOCUMENT_IN_USE      = "Document '%s' cannot be deleted while an extraction is in progress.";
```

### `AIProperties` + `ReductoConfig` changes

Add `preconWebhookChannel` property to `AIProperties`:

```java
/** Webhook channel for precon pipeline Reducto jobs. Separate from the email pipeline channel. */
private String preconWebhookChannel = "precon";
```

Add `@Bean @Qualifier("preconReductoClient") ReductoClient` in `ReductoConfig`:

```java
@Bean
@Qualifier("preconReductoClient")
public ReductoClient preconReductoClient(AIProperties aiProperties,
                                          OkHttpClient httpClient,
                                          ObjectMapper objectMapper) {
    return new ReductoClient(
        aiProperties.getApiKey(),
        httpClient,
        objectMapper,
        aiProperties.getPreconWebhookChannel()
    );
}
```

The existing `reductoClient` bean (default channel) is untouched.

### Tender status gate in `TenderExtractionAdapter`

`verifyOwnership` currently checks only company ownership. This PR adds a tender status check:

```java
// In TenderExtractionAdapter.verifyOwnership — add after company check:
Set<String> ACTIVE_STATUSES = Set.of(
    TenderStatus.PENDING.getValue(),
    TenderStatus.SUBMITTED.getValue()
);
if (!ACTIVE_STATUSES.contains(tender.getStatus())) {
    throw new BadRequestException(
        ApiErrorMessages.EXTRACTION_TENDER_NOT_ACTIVE_CODE,
        ApiErrorMessages.EXTRACTION_TENDER_NOT_ACTIVE.formatted(
            entityId, tender.getStatus()));
}
```

### Document deletion guard in `TenderDocumentServiceImpl`

Before soft-deleting a document, check that no extraction with status `processing` references it:

```java
// In TenderDocumentServiceImpl.deleteDocument — add before soft-delete call:
boolean inProgress = extractionRepository.existsProcessingExtractionForDocument(documentId);
if (inProgress) {
    throw new BadRequestException(
        ApiErrorMessages.DOCUMENT_IN_USE_CODE,
        ApiErrorMessages.DOCUMENT_IN_USE.formatted(documentId));
}
```

This requires one new method on `ExtractionRepository`:

```java
/** Returns true if any non-deleted extraction with status='processing' references this documentId in its document_ids JSONB array. */
boolean existsProcessingExtractionForDocument(String documentId);
```

SQL: `WHERE status='processing' AND document_ids @> '["<documentId>"]'::jsonb AND deleted_at IS NULL`.

### Files to create/modify in PR #1

```
NEW    flyway/V3.7__add_pipeline_state_to_extractions.sql
NEW    libs/api-tosspaper/.../precon/DocumentPipelineState.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineRepository.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineRepositoryImpl.java
MODIFY libs/api-tosspaper/.../precon/ExtractionFieldRepository.java        (add bulkInsert)
MODIFY libs/api-tosspaper/.../precon/ExtractionFieldRepositoryImpl.java    (implement bulkInsert)
MODIFY libs/api-tosspaper/.../precon/ExtractionRepository.java             (add existsProcessingExtractionForDocument)
MODIFY libs/api-tosspaper/.../precon/ExtractionRepositoryImpl.java         (implement it)
MODIFY libs/api-tosspaper/.../precon/TenderExtractionAdapter.java          (add status gate)
MODIFY libs/api-tosspaper/.../precon/TenderDocumentServiceImpl.java        (add deletion guard)
MODIFY libs/api-tosspaper/.../common/ApiErrorMessages.java                 (7 new constants)
MODIFY libs/ai-engine/.../properties/AIProperties.java                     (preconWebhookChannel)
MODIFY libs/ai-engine/.../config/ReductoConfig.java                        (preconReductoClient bean)
```

### Edge Case Gate: READY

All 28 questions pass. Additive-only changes. No breaking changes to existing APIs.
Status gate is a new validation in `TenderExtractionAdapter`, not in the service layer — correctly placed at the adapter boundary.

---

## PR #2 — Pipeline Trigger (SQS publish + handler)

### REVISED: Standalone trigger service, ExtractionServiceImpl NOT modified

Per user feedback: the trigger must be a **standalone service** separate from
`ExtractionServiceImpl`. The user plans to deprecate the SQS-based trigger once the
pipeline is stable. `ExtractionServiceImpl` is not touched.

### What it does

A new `ExtractionPipelineTriggerService` publishes an SQS message when called.
A new `ExtractionPipelineHandler` receives the message and delegates to
`ExtractionPipelineService.run(ExtractionPipelineContext)`.

The trigger service is NOT wired inside `ExtractionServiceImpl`. It is called by an
event mechanism (Spring `ApplicationEventPublisher` or direct invocation by a future
coordinator). For now, the `ExtractionController` calls it directly after `createExtraction`
succeeds — keeping `ExtractionServiceImpl` clean. This is a thin wrapper, not business logic.

### `ExtractionPipelineTriggerMessage` record

```java
/**
 * SQS message payload for the precon extraction pipeline trigger queue.
 * Contains ALL context the pipeline handler needs — no DB re-fetching required.
 *
 * Fields:
 *   extractionId    — the extraction to process
 *   entityId        — the tender ID (for logging/correlation)
 *   companyId       — for access logging and metrics
 *   documents       — full document context: ID, S3 bucket, S3 key, fileName, contentType
 *   requestedFields — field names to extract (null = extract all fields)
 */
public record ExtractionPipelineTriggerMessage(
    String extractionId,
    String entityId,
    String companyId,
    List<DocumentContext> documents,
    List<String> requestedFields
) {
    /**
     * Per-document context embedded in the trigger message.
     * Provides everything the pipeline needs to download and classify each document
     * without querying the DB or S3 for metadata.
     */
    public record DocumentContext(
        String documentId,
        String s3Bucket,
        String s3Key,
        String fileName,
        String contentType
    ) {}
}
```

This record is serialized to JSON by `MessagePublisher` and sent to the SQS queue.
The handler deserializes it and builds `ExtractionPipelineContext` from it.

### `ExtractionPipelineContext` record

```java
/**
 * Runtime context passed to ExtractionPipelineService.run().
 * Contains all data needed for pipeline execution — no DB fetch required inside run().
 */
public record ExtractionPipelineContext(
    String extractionId,
    String entityId,
    String companyId,
    List<ExtractionPipelineTriggerMessage.DocumentContext> documents,
    List<String> requestedFields  // null = extract all
) {}
```

### `ExtractionPipelineService` interface

```java
public interface ExtractionPipelineService {
    /**
     * Runs the full extraction pipeline for the given context.
     * Idempotent: guards on extraction status='pending' before starting.
     */
    void run(ExtractionPipelineContext context);
}
```

### `ExtractionPipelineTriggerService`

```java
@Service
@RequiredArgsConstructor
public class ExtractionPipelineTriggerService {

    private static final String QUEUE_NAME = "precon-extraction-trigger";

    private final MessagePublisher messagePublisher;
    private final ObjectMapper objectMapper;
    private final TenderDocumentRepository tenderDocumentRepository;

    /**
     * Publishes an extraction trigger message to SQS.
     * Loads document S3 metadata here so the pipeline handler never needs DB access.
     *
     * @param extractionId   the extraction record ID
     * @param companyId      company ID string
     * @param entityId       tender ID string
     * @param documentIds    list of document IDs to include
     * @param requestedFields field names filter (null = all)
     */
    public void trigger(String extractionId, String companyId, String entityId,
                        List<String> documentIds, List<String> requestedFields) {
        List<TenderDocumentsRecord> docs = tenderDocumentRepository.findByIds(documentIds);
        List<ExtractionPipelineTriggerMessage.DocumentContext> contexts = docs.stream()
            .map(d -> new ExtractionPipelineTriggerMessage.DocumentContext(
                d.getId(), tenderBucket(d), d.getS3Key(), d.getFileName(), d.getContentType()))
            .toList();

        ExtractionPipelineTriggerMessage message = new ExtractionPipelineTriggerMessage(
            extractionId, entityId, companyId, contexts, requestedFields);

        messagePublisher.publish(QUEUE_NAME, message);
        log.info("Published extraction trigger: extractionId={}, docCount={}",
                 extractionId, contexts.size());
    }
}
```

The S3 bucket is resolved from a config property (not hardcoded). `TenderDocumentRepository.findByIds` already exists.

### `ExtractionPipelineHandler`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPipelineHandler implements MessageHandler<Map<String, Object>> {

    private static final String QUEUE_NAME = "precon-extraction-trigger";

    private final ExtractionPipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @Override
    public String getQueueName() { return QUEUE_NAME; }

    @Override
    public void handle(Map<String, Object> message) {
        ExtractionPipelineTriggerMessage trigger =
            objectMapper.convertValue(message, ExtractionPipelineTriggerMessage.class);

        if (trigger.extractionId() == null || trigger.extractionId().isBlank()) {
            log.warn("Received pipeline trigger with missing extractionId, skipping");
            return;
        }
        if (trigger.documents() == null || trigger.documents().isEmpty()) {
            log.warn("Received pipeline trigger with no documents, extractionId={}, skipping",
                     trigger.extractionId());
            return;
        }

        ExtractionPipelineContext context = new ExtractionPipelineContext(
            trigger.extractionId(),
            trigger.entityId(),
            trigger.companyId(),
            trigger.documents(),
            trigger.requestedFields()
        );

        pipelineService.run(context);
    }
}
```

Note: `MessageHandler<Map<String, Object>>` — uses `Map<String, Object>` not `Map<String, String>`, confirmed by user.

### `ExtractionController` — minimal change

`ExtractionController.createExtraction` calls `ExtractionPipelineTriggerService.trigger(...)` after the service call succeeds. This is the ONLY change to existing files in PR #2. No change to `ExtractionServiceImpl`.

```java
// In ExtractionController.createExtraction (after service.createExtraction):
pipelineTriggerService.trigger(
    result.extraction().getId().toString(),
    companyId.toString(),
    request.getEntityId().toString(),
    result.extraction().getDocumentIds().stream().map(UUID::toString).toList(),
    result.extraction().getRequestedFields()
);
```

### SQS config (application YAML)

```yaml
aws.sqs.queues:
  precon-extraction-trigger:
    enabled: true
    visibilityTimeoutSeconds: 300   # 5 min — pipeline can be slow for large doc sets
    maxReceiveCount: 3
    pollDelaySeconds: 20
    maxMessages: 5
```

### Files to create/modify in PR #2

```
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineTriggerService.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineTriggerMessage.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineContext.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineService.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineHandler.java
MODIFY libs/api-tosspaper/.../precon/ExtractionController.java              (add trigger call)
```

### Files NOT touched

- `ExtractionServiceImpl.java` — not touched (user requirement)

### Edge Case Gate: READY

| # | Question | Answer | Justification |
|---|----------|--------|---------------|
| 1 | Idempotent? | YES | PR #3's `updateProcessingStarted` guards on `WHERE status='pending'` |
| 2 | Safe to retry? | YES | SQS re-delivery hits idempotency guard in `run()` |
| 3 | Rate-limited? | N/A | Internal SQS — no external client hammering |
| 4 | Auth protected? | N/A | SQS handler — no HTTP endpoint |
| 5 | Concurrent-safe? | YES | Status guard in PR #3 is atomic DB update |
| 6 | Handles infra failure? | YES | SQS DLQ after 3 retries |
| 7 | Transaction-safe? | YES | Trigger publish is outside transaction — extraction already committed before publish |
| 8 | Data integrity? | YES | Documents pre-validated at create time |
| 9 | Null/empty inputs handled? | YES | Handler guards on extractionId and documents |
| 10 | Not-found handled? | YES | PR #3 loads extraction and guards status |
| 11 | Invalid state transitions blocked? | YES | `WHERE status='pending'` guard |
| 12 | Payload/list size limits? | YES | SQS 256KB limit; 200 docs max cap from TenderExtractionAdapter |
| 13 | Pagination edge cases? | N/A | No pagination in trigger |
| 14 | Cross-tenant isolation? | YES | extractionId is scoped to company at create time |
| 15 | Audit trail? | YES | `log.info` on trigger publish with extractionId + docCount |
| 16 | Cascading deletes? | N/A | No deletes in this PR |
| 17 | Timeout handling? | YES | SQS visibility timeout=300s; pipeline will mark failed on exception |
| 18 | Error response consistency? | N/A | No HTTP response from handler |
| 19 | Logging sufficient? | YES | extractionId + docCount logged at publish and receive |
| 20 | Backward compatible? | YES | ExtractionController change is additive |
| 21 | Best practice? | YES | Trigger as standalone service, context object passed, no business logic in controller |
| 22 | Cost efficient? | YES | One SQS message per extraction; document S3 metadata loaded once at trigger time |
| 23 | Best tradeoff? | YES | Simplest decoupled design; trigger service is deprecation-ready |
| 24 | Scalable? | YES | SQS scales linearly; handler is stateless |
| 25 | Query performance? | YES | `findByIds` is an IN query on primary key |
| 26 | Memory efficient? | YES | Message payload bounded by max 200 documents * small metadata |
| 27 | Minimal blast radius? | YES | Trigger failure does not affect extraction record state |
| 28 | Reversible? | YES | Trigger service can be disabled by toggling SQS queue config |

**Verdict: READY**

---

## PR #3 — Document Classification + Reducto Dispatch

### REVISED: Parallelism required, context-driven, provider-agnostic

Key changes from original plan:
1. `run(ExtractionPipelineContext)` — takes a context object, not an extractionId string
2. Provider-agnostic abstraction — `DocumentDispatcher` interface wraps Reducto
3. Parallel document processing — `CompletableFuture` with bounded thread pool
4. Pipeline state is built from context at startup, no DB re-fetch
5. Correlation uses dedicated `reductoJobId` and `documentId` fields in `DocumentPipelineState` — no pipe-delimited string

### What it does

Implements `ExtractionPipelineService.run(ExtractionPipelineContext)`:

1. Guard: `updateProcessingStarted(WHERE status='pending')` — returns 0 if already started (idempotency on SQS retry)
2. Build initial `pipeline_state` JSONB from context documents (all status=`pending`)
3. Persist initial pipeline state
4. Classify + dispatch each document **in parallel** (see parallelism section)
5. Persist final pipeline state
6. If all docs skipped/failed → `updateFailed` immediately; else wait for webhooks

### Parallelism design

The user asked for concrete options. We implement **bounded CompletableFuture + dedicated thread pool** (Option A). Rationale: fits Spring's existing thread model, no new infra, bounded to prevent starvation.

```java
// In ExtractionPipelineServiceImpl constructor or @Bean:
private final ExecutorService pipelineExecutor = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors() * 2,  // e.g. 8 threads on 4-core
    new ThreadFactoryBuilder().setNameFormat("pipeline-%d").build()
);
```

Document processing loop:

```java
List<CompletableFuture<DocumentPipelineState>> futures = context.documents().stream()
    .map(docCtx -> CompletableFuture.supplyAsync(
        () -> processDocument(docCtx, context.requestedFields(), context.extractionId()),
        pipelineExecutor))
    .toList();

List<DocumentPipelineState> states = futures.stream()
    .map(f -> {
        try {
            return f.get(DOCUMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.error("Document processing timed out for extraction {}", extractionId);
            return buildFailedState(docCtx, "Processing timed out");
        } catch (Exception e) {
            log.error("Document processing failed for extraction {}", extractionId, e);
            return buildFailedState(docCtx, e.getMessage());
        }
    })
    .toList();
```

Each `processDocument` call:
1. Download first 1KB from S3 for classification (range request via `S3Client` — same pattern as `DocumentUploadProcessor.downloadHeader`)
2. Classify via `DocumentClassifier.classify(fileName, contentSnippet)` — returns doc type string
3. Check relevance via `FieldToDocumentTypeMapping.isRelevant(docType, requestedFields)` — see section below
4. If not relevant → return state with status=`skipped`
5. If relevant:
   a. Download full file from S3
   b. Upload to Reducto via `DocumentDispatcher.dispatch(...)` — returns `reductoJobId` + `reductoFileId`
   c. Return state with status=`started`, reductoJobId set

Per-document timeout: 120 seconds (upload to Reducto can be slow for large PDFs).

### Provider-agnostic `DocumentDispatcher` interface

```java
/**
 * Abstraction for dispatching a document to an AI extraction provider.
 * Implement this interface to swap Reducto for another provider without
 * changing ExtractionPipelineServiceImpl.
 */
public interface DocumentDispatcher {

    /**
     * Uploads the document bytes to the provider and starts an async extraction task.
     *
     * @param extractionId   for correlation logging
     * @param documentId     for correlation — stored in pipeline_state
     * @param fileBytes      full file content
     * @param fileName       original file name
     * @param contentType    MIME type
     * @param schema         extraction schema JSON string
     * @param systemPrompt   extraction prompt
     * @return DispatchResult with jobId and fileId
     */
    DispatchResult dispatch(String extractionId, String documentId,
                            byte[] fileBytes, String fileName, String contentType,
                            String schema, String systemPrompt) throws Exception;

    record DispatchResult(String jobId, String fileId) {}
}
```

`ReductoDocumentDispatcher` implements `DocumentDispatcher`, injecting
`@Qualifier("preconReductoClient") ReductoClient`. It calls `requestPresignedUrl()`,
`uploadFile()`, `createAsyncExtractTask()` exactly as `ReductoProcessingService` does.
Swapping to a different provider = implement `DocumentDispatcher` with the new provider's
client and change the `@Primary` annotation.

### Webhook correlation — no pipe-delimited strings

Reducto `assignedId` metadata is set to `documentId` only (not `"docId|extractionId"`).
The `extractionId` is stored separately in the Reducto metadata as a distinct field:

```java
// In ReductoDocumentDispatcher.dispatch():
Map<String, String> metadata = Map.of(
    "documentId",   documentId,
    "extractionId", extractionId
);
// Passed to reductoClient.createAsyncExtractTask as part of ReductoAsyncConfig.metadata
```

PR #4 reads both fields independently from the webhook payload metadata — no string splitting.

### Field-to-document-type mapping and "skipped" semantics

A document is **skipped** when classification determines it is irrelevant for the
requested extraction fields. Specifically:

- If `requestedFields` is null or empty → all documents are relevant (extract everything)
- If `requestedFields` is non-empty → check whether the doc type matches any field's
  expected document types in `FieldToDocumentTypeMapping`
- If doc type is `"unknown"` → include it (conservative; never skip unknowns)
- If no requested fields map to this doc type → mark status=`skipped`

These documents are filters to determine **which documents get sent to Reducto** — they are
the tender's uploaded files (RFP PDFs, addenda, drawings, etc.).

```java
public final class FieldToDocumentTypeMapping {

    // Maps field name -> set of document types where that field typically appears
    private static final Map<String, Set<String>> FIELD_TO_DOC_TYPES = Map.ofEntries(
        Map.entry("name",               Set.of("rfp", "addendum", "contract")),
        Map.entry("reference_number",   Set.of("rfp", "addendum", "contract")),
        Map.entry("location",           Set.of("rfp", "addendum", "contract")),
        Map.entry("scope_of_work",      Set.of("rfp", "specification", "contract")),
        Map.entry("delivery_method",    Set.of("rfp", "contract")),
        Map.entry("currency",           Set.of("rfp", "contract", "addendum")),
        Map.entry("closing_date",       Set.of("rfp", "addendum")),
        Map.entry("events",             Set.of("rfp", "addendum", "schedule")),
        Map.entry("start_date",         Set.of("rfp", "contract", "schedule")),
        Map.entry("completion_date",    Set.of("rfp", "contract", "schedule")),
        Map.entry("inquiry_deadline",   Set.of("rfp", "addendum")),
        Map.entry("submission_method",  Set.of("rfp", "addendum")),
        Map.entry("submission_url",     Set.of("rfp", "addendum")),
        Map.entry("bonds",              Set.of("rfp", "contract")),
        Map.entry("conditions",         Set.of("rfp", "contract", "specification")),
        Map.entry("parties",            Set.of("rfp", "contract")),
        Map.entry("liquidated_damages", Set.of("rfp", "contract"))
    );

    /**
     * Returns true if docType is relevant for at least one of the requested fields.
     * Null/empty requestedFields = all docs relevant.
     * Unknown docType = always relevant (conservative).
     */
    public static boolean isRelevant(String docType, List<String> requestedFields) {
        if (requestedFields == null || requestedFields.isEmpty()) return true;
        if ("unknown".equals(docType)) return true;
        return requestedFields.stream().anyMatch(field -> {
            Set<String> types = FIELD_TO_DOC_TYPES.get(field);
            return types != null && types.contains(docType);
        });
    }
}
```

### Reducto schema for tender fields (Q5 answer)

Schema file: `schema-prompts/schemas/tender_extraction.json`

```json
{
  "type": "object",
  "properties": {
    "name":               {"type": "string", "description": "Project or tender name"},
    "reference_number":   {"type": "string", "description": "Tender reference or project number"},
    "location":           {"type": "string", "description": "Project location or address"},
    "scope_of_work":      {"type": "string", "description": "Summary of work to be performed"},
    "delivery_method":    {"type": "string", "description": "Construction delivery method (e.g. design-build, stipulated sum)"},
    "currency":           {"type": "string", "description": "Currency code (e.g. CAD, USD)"},
    "closing_date":       {"type": "string", "format": "date-time", "description": "Tender closing date and time"},
    "events":             {"type": "array", "items": {"type": "object", "properties": {"name": {"type": "string"}, "date": {"type": "string", "format": "date-time"}, "location": {"type": "string"}}}, "description": "Key project events (site visits, pre-bid meetings, etc.)"},
    "start_date":         {"type": "string", "format": "date-time", "description": "Project start date"},
    "completion_date":    {"type": "string", "format": "date-time", "description": "Project completion date"},
    "inquiry_deadline":   {"type": "string", "format": "date-time", "description": "Last date for submitting questions"},
    "submission_method":  {"type": "string", "description": "How bids are submitted (e.g. online portal, email, hand delivery)"},
    "submission_url":     {"type": "string", "description": "URL for online bid submission"},
    "bonds":              {"type": "array", "items": {"type": "object", "properties": {"type": {"type": "string"}, "percentage": {"type": "number"}, "description": {"type": "string"}}}, "description": "Required bonds (bid bond, performance bond, etc.)"},
    "conditions":         {"type": "array", "items": {"type": "string"}, "description": "Contract conditions or special requirements"},
    "parties":            {"type": "array", "items": {"type": "object", "properties": {"name": {"type": "string"}, "role": {"type": "string"}, "contact": {"type": "string"}}}, "description": "Key parties (owner, consultant, general contractor, etc.)"},
    "liquidated_damages": {"type": "string", "description": "Liquidated damages clause and amount"}
  }
}
```

Schema is loaded at startup via `JsonSchemaLoader`. Only fields matching `requestedFields`
are sent to Reducto (schema pruning reduces cost for partial extractions).

### Files to create in PR #3

```
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineServiceImpl.java
NEW    libs/api-tosspaper/.../precon/DocumentDispatcher.java
NEW    libs/api-tosspaper/.../precon/ReductoDocumentDispatcher.java
NEW    libs/api-tosspaper/.../precon/DocumentClassifier.java
NEW    libs/api-tosspaper/.../precon/OpenAiDocumentClassifier.java
NEW    libs/api-tosspaper/.../precon/FieldToDocumentTypeMapping.java
NEW    libs/api-tosspaper/.../precon/TenderExtractionSchemaLoader.java
NEW    schema-prompts/schemas/tender_extraction.json
NEW    schema-prompts/prompts/tender_extraction.prompt
```

### Files NOT touched (owned by other PRs)

- `ExtractionRepository.java` — PR #1 only
- `ExtractionServiceImpl.java` — not touched at all
- `ExtractionController.java` — PR #2 only

### Dependencies injected (ExtractionPipelineServiceImpl)

- `ExtractionPipelineRepository` (from PR #1)
- `DocumentClassifier` (new interface, this PR)
- `DocumentDispatcher` (new interface, this PR)
- `TenderExtractionSchemaLoader` (this PR)
- `S3Client` (existing Spring bean — already used in `DocumentUploadProcessor`)
- `ObjectMapper`
- `ExecutorService pipelineExecutor` (constructed in this class — not injected)

### Edge Case Gate: READY

| # | Question | Answer | Justification |
|---|----------|--------|---------------|
| 1 | Idempotent? | YES | `updateProcessingStarted WHERE status='pending'` — returns 0 if replay |
| 2 | Safe to retry? | YES | Status guard prevents re-processing; partial state cleaned on retry |
| 3 | Rate-limited? | N/A | Internal processor — no external API endpoint |
| 4 | Auth protected? | N/A | Not an HTTP endpoint |
| 5 | Concurrent-safe? | YES | Atomic DB status update via optimistic guard |
| 6 | Handles infra failure? | YES | Per-doc timeout + catch wraps S3/Reducto errors; extraction marked failed |
| 7 | Transaction-safe? | YES | Pipeline state written after all futures complete — partial state acceptable (pipeline is resumable via retry) |
| 8 | Data integrity? | YES | All doc IDs pre-validated at extraction create time; no orphan risk |
| 9 | Null/empty inputs handled? | YES | Null requestedFields = all docs relevant; empty docs checked in handler |
| 10 | Not-found handled? | YES | S3 NoSuchKeyException caught per-doc, marks that doc failed |
| 11 | Invalid state transitions blocked? | YES | Status guard at entry; Reducto dispatch only if status=pending |
| 12 | Payload/list size limits? | YES | Bounded thread pool prevents memory exhaustion; 200 doc cap from adapter |
| 13 | Pagination edge cases? | N/A | No pagination |
| 14 | Cross-tenant isolation? | YES | extractionId scoped to company at create time; context carries companyId |
| 15 | Audit trail? | YES | log.info per document dispatched/skipped with extractionId + documentId |
| 16 | Cascading deletes? | N/A | No deletes here |
| 17 | Timeout handling? | YES | Per-doc 120s CompletableFuture timeout; marks failed on timeout |
| 18 | Error response consistency? | N/A | No HTTP response |
| 19 | Logging sufficient? | YES | Per-doc status logged; skipped/dispatched/failed all traced |
| 20 | Backward compatible? | YES | New files only; no existing files modified |
| 21 | Best practice? | YES | CompletableFuture + bounded executor is standard Java parallelism |
| 22 | Cost efficient? | YES | Only relevant docs sent to Reducto; schema pruned per requestedFields |
| 23 | Best tradeoff? | YES | CompletableFuture chosen over virtual threads (not yet standard) and Reactor (not in use here) |
| 24 | Scalable? | YES | Thread pool sized to CPU*2; stateless service instances scale horizontally |
| 25 | Query performance? | N/A | No DB queries inside the loop except updatePipelineState |
| 26 | Memory efficient? | YES | Full file bytes loaded per-doc, not all at once; processed and GC'd per future |
| 27 | Minimal blast radius? | YES | Per-doc failure isolated; other docs continue |
| 28 | Reversible? | YES | Schema loader and dispatcher are swappable; thread pool is local |

**Verdict: READY**

---

## PR #4 — Field Persistence + Extraction Completion

### REVISED: Proper correlation fields, security validation documented

Key changes from original plan:
1. Webhook payload metadata carries `documentId` and `extractionId` as separate fields (no pipe split)
2. Svix signature verification is documented explicitly
3. `ReductoWebhookPayload.metadata` is `Map<String, Object>` not `Map<String, String>`

### What it does

Receives Reducto webhook for precon jobs → parses results → inserts `extraction_fields`
rows → transitions extraction to `completed`/`failed`.

### Webhook security validation

The existing `ReductoWebhookController` (`POST /webhooks/reducto`) does NOT verify Svix
signatures. The new `PreconReductoWebhookController` (`POST /webhooks/reducto/precon`)
MUST verify Svix signatures before processing.

```java
@PostMapping
public ResponseEntity<Void> handlePreconWebhook(
        @RequestBody String rawBody,
        @RequestHeader("svix-id") String svixId,
        @RequestHeader("svix-timestamp") String svixTimestamp,
        @RequestHeader("svix-signature") String svixSignature) {

    // Step 1: Verify Svix signature
    boolean valid = svixVerifier.verify(rawBody, svixId, svixTimestamp, svixSignature);
    if (!valid) {
        log.warn("Svix signature verification failed for precon webhook");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // Step 2: Deserialize and delegate
    ReductoWebhookPayload payload = objectMapper.readValue(rawBody, ReductoWebhookPayload.class);
    log.info("Received precon Reducto webhook: jobId={}, status={}",
             payload.getJobId(), payload.getStatus());
    webhookService.processWebhook(payload);
    return ResponseEntity.accepted().build();
}
```

`SvixVerifier` is an injectable Spring bean configured with the Svix signing secret for
the `precon` channel. The Svix Java SDK provides `Webhook.verify(payload, headers)`.
Secret is injected via `AIProperties.preconSvixSecret` (new property).

### Webhook payload metadata shape

`ReductoWebhookPayload.metadata` must be `Map<String, Object>` (confirmed by user):

```java
@Data
public class ReductoWebhookPayload {
    @JsonProperty("job_id") private String jobId;
    private String status;
    private Map<String, Object> metadata;   // Map<String, Object> not Map<String, String>
    private Object result;                   // the full extraction result when status=succeeded
}
```

### Correlation without pipe-delimited strings

Metadata set by PR #3 contains two distinct fields:

```json
{
  "documentId":   "abc-123",
  "extractionId": "extr-456"
}
```

PR #4 reads them independently:

```java
String documentId   = (String) payload.getMetadata().get("documentId");
String extractionId = (String) payload.getMetadata().get("extractionId");
```

No string splitting. If either field is missing → log warning and return (unknown job, skip).

### `PreconExtractionWebhookServiceImpl.processWebhook` flow

```
1. Extract documentId + extractionId from metadata
2. If either null → log warn, return (idempotent skip)
3. Load extraction via ExtractionRepository.findById(extractionId)
4. If extraction status is already terminal (completed/failed/cancelled) → return (idempotent)
5. If Reducto status is not 'succeeded' and not 'failed' → return (non-final webhook, ignore)
6. Load current pipeline_state from extraction record
7. Deserialize JSONB → List<DocumentPipelineState> (typed record, not Map)
8. Find the DocumentPipelineState entry for this documentId
9. Update its status and reductoJobId
10. If Reducto status = 'succeeded':
    a. Extract result from payload (no extra fetch needed — result is in webhook body)
    b. Parse with TenderFieldPopulator → List<ExtractionFieldsRecord>
    c. bulkInsert fields (@Transactional — fields + pipeline_state + status in one transaction)
    d. Update pipeline_state entry to status=completed
11. If Reducto status = 'failed':
    d. Update pipeline_state entry to status=failed, error=Reducto error message
12. Persist updated pipeline_state via ExtractionPipelineRepository.updatePipelineState
13. Check if all pipeline_state entries are terminal:
    a. At least one completed → ExtractionPipelineRepository.updateCompleted
    b. All failed or skipped → ExtractionPipelineRepository.updateFailed (with error summary)
```

### `TenderFieldPopulator`

Pure domain class. No Spring dependencies. Takes Reducto result JSON + returns
`List<ExtractionFieldsRecord>`:

```java
public class TenderFieldPopulator {

    public List<ExtractionFieldsRecord> populate(
            String extractionId,
            String documentId,
            Object reductoResult,
            ObjectMapper objectMapper) {

        // reductoResult is the parsed JSON from the webhook payload result field
        // Maps each top-level key → ExtractionFieldsRecord
        // Handles all 17 TenderFieldName enum values
        // Sets field_type, proposed_value, citations from Reducto output
        ...
    }
}
```

### Files to create in PR #4

```
NEW    libs/api-tosspaper/.../precon/PreconReductoWebhookController.java
NEW    libs/api-tosspaper/.../precon/PreconExtractionWebhookService.java
NEW    libs/api-tosspaper/.../precon/PreconExtractionWebhookServiceImpl.java
NEW    libs/api-tosspaper/.../precon/TenderFieldPopulator.java
NEW    libs/api-tosspaper/.../precon/SvixVerifier.java
```

### Files NOT touched (owned by other PRs)

- `ExtractionFieldRepository.java` — bulkInsert added in PR #1
- `ExtractionPipelineRepository.java` — created in PR #1
- `ExtractionRepository.java` — PR #1 only
- `libs/ai-engine/.../api/ReductoWebhookController.java` — not touched

### Dependencies injected (PreconExtractionWebhookServiceImpl)

- `ExtractionRepository`
- `ExtractionPipelineRepository`
- `ExtractionFieldRepository`
- `TenderFieldPopulator`
- `ObjectMapper`

### Edge Case Gate: READY

| # | Question | Answer | Justification |
|---|----------|--------|---------------|
| 1 | Idempotent? | YES | Status check: terminal extraction → no-op; duplicate webhook → pipeline_state entry already terminal |
| 2 | Safe to retry? | YES | Svix retries on non-2xx; idempotency guard prevents double insert |
| 3 | Rate-limited? | N/A | Svix manages delivery rate; no client-facing throttle needed |
| 4 | Auth protected? | YES | Svix signature verification via SvixVerifier before any processing |
| 5 | Concurrent-safe? | YES | @Transactional on processWebhook; pipeline_state update + field insert atomic |
| 6 | Handles infra failure? | YES | Non-2xx → Svix retries; @Transactional prevents partial commit |
| 7 | Transaction-safe? | YES | @Transactional wraps step 8-13; fields + pipeline_state + status in one transaction |
| 8 | Data integrity? | YES | bulkInsert uses ON CONFLICT DO NOTHING; FK from extraction_fields to extractions enforced |
| 9 | Null/empty inputs handled? | YES | Missing documentId/extractionId in metadata → early return |
| 10 | Not-found handled? | YES | ExtractionRepository.findById throws NotFoundException → caught, logged, return |
| 11 | Invalid state transitions blocked? | YES | Terminal extraction status check at step 4 |
| 12 | Payload/list size limits? | N/A | Reducto controls webhook payload size; fields per doc bounded by schema |
| 13 | Pagination edge cases? | N/A | No pagination |
| 14 | Cross-tenant isolation? | YES | extractionId lookup scoped to DB; pipeline_state contains no cross-tenant data |
| 15 | Audit trail? | YES | log.info on each webhook received and each extraction status transition |
| 16 | Cascading deletes? | YES | extraction_fields FK ON DELETE CASCADE from extractions — handled by DB |
| 17 | Timeout handling? | YES | Controller returns 202 immediately; processing is synchronous but bounded |
| 18 | Error response consistency? | YES | 401 on bad signature, 202 on success, exceptions bubble to ErrorTranslator |
| 19 | Logging sufficient? | YES | jobId + documentId + extractionId logged at each step |
| 20 | Backward compatible? | YES | New controller and endpoint; existing /webhooks/reducto untouched |
| 21 | Best practice? | YES | Svix SDK verification follows Svix's recommended pattern |
| 22 | Cost efficient? | YES | Result in webhook body — no extra Reducto API call to fetch result |
| 23 | Best tradeoff? | YES | Synchronous webhook processing is simplest; Reducto retries handle failures |
| 24 | Scalable? | YES | Stateless service; multiple instances handle concurrent webhooks safely |
| 25 | Query performance? | YES | findById by PK; bulkInsert is batch; pipeline_state update is PK update |
| 26 | Memory efficient? | YES | Result JSON deserialized and GC'd within single request |
| 27 | Minimal blast radius? | YES | Webhook handler failure does not affect other extractions |
| 28 | Reversible? | YES | New endpoint only; can be disabled without schema changes |

**Verdict: READY**

---

## Complete File Map (revised)

### PR #1 — Foundation
```
NEW    flyway/V3.7__add_pipeline_state_to_extractions.sql
NEW    libs/api-tosspaper/.../precon/DocumentPipelineState.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineRepository.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineRepositoryImpl.java
MODIFY libs/api-tosspaper/.../precon/ExtractionFieldRepository.java        (add bulkInsert)
MODIFY libs/api-tosspaper/.../precon/ExtractionFieldRepositoryImpl.java    (implement bulkInsert)
MODIFY libs/api-tosspaper/.../precon/ExtractionRepository.java             (add existsProcessingExtractionForDocument)
MODIFY libs/api-tosspaper/.../precon/ExtractionRepositoryImpl.java         (implement it)
MODIFY libs/api-tosspaper/.../precon/TenderExtractionAdapter.java          (tender status gate)
MODIFY libs/api-tosspaper/.../precon/TenderDocumentServiceImpl.java        (deletion guard)
MODIFY libs/api-tosspaper/.../common/ApiErrorMessages.java                 (7 new constants)
MODIFY libs/ai-engine/.../properties/AIProperties.java                     (preconWebhookChannel + preconSvixSecret)
MODIFY libs/ai-engine/.../config/ReductoConfig.java                        (preconReductoClient bean)
```

### PR #2 — Pipeline Trigger
```
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineContext.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineService.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineTriggerMessage.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineTriggerService.java
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineHandler.java
MODIFY libs/api-tosspaper/.../precon/ExtractionController.java              (add trigger call only)
```

### PR #3 — Classification + Reducto Dispatch
```
NEW    libs/api-tosspaper/.../precon/ExtractionPipelineServiceImpl.java
NEW    libs/api-tosspaper/.../precon/DocumentDispatcher.java
NEW    libs/api-tosspaper/.../precon/ReductoDocumentDispatcher.java
NEW    libs/api-tosspaper/.../precon/DocumentClassifier.java
NEW    libs/api-tosspaper/.../precon/OpenAiDocumentClassifier.java
NEW    libs/api-tosspaper/.../precon/FieldToDocumentTypeMapping.java
NEW    libs/api-tosspaper/.../precon/TenderExtractionSchemaLoader.java
NEW    schema-prompts/schemas/tender_extraction.json
NEW    schema-prompts/prompts/tender_extraction.prompt
```

### PR #4 — Field Persistence + Completion
```
NEW    libs/api-tosspaper/.../precon/PreconReductoWebhookController.java
NEW    libs/api-tosspaper/.../precon/PreconExtractionWebhookService.java
NEW    libs/api-tosspaper/.../precon/PreconExtractionWebhookServiceImpl.java
NEW    libs/api-tosspaper/.../precon/TenderFieldPopulator.java
NEW    libs/api-tosspaper/.../precon/SvixVerifier.java
```

---

## Summary of Changes From Original Plan

| # | Feedback Item | Original | Revised |
|---|---------------|----------|---------|
| 1 | ExtractionRepository rename | Pipeline methods added to ExtractionRepository | New `ExtractionPipelineRepository` separate from ExtractionRepository |
| 2 | Full SQS message context | `{"extractionId": "<id>"}` only | Full `ExtractionPipelineTriggerMessage` with all doc context (no re-fetch) |
| 3 | pipeline_state Java type | Implied Map | Explicit `DocumentPipelineState` Java record, documented as NOT a Map |
| 4 | run() takes context | `void run(String extractionId)` | `void run(ExtractionPipelineContext context)` |
| 5 | Standalone trigger service | Publish in ExtractionServiceImpl | New `ExtractionPipelineTriggerService`; ExtractionServiceImpl NOT modified |
| 6 | Provider-agnostic pipeline | Direct ReductoClient calls in ServiceImpl | `DocumentDispatcher` interface; `ReductoDocumentDispatcher` implements it |
| 7 | Parallelism required | Sequential MVP | CompletableFuture + bounded fixed thread pool |
| 8 | Webhook security validation | Not documented | Svix signature verification via `SvixVerifier` bean, documented in detail |
| 9 | Correlation — no pipe-delimited ID | `"docId|extractionId"` in assignedId | Separate `documentId` and `extractionId` keys in Reducto metadata |
| 10 | Field-to-doc-type clarification | Implied | Explicitly documented as filter for which docs go to Reducto |
| 11 | Skipped status clarification | Not explained | Skipped = classification determines doc irrelevant for requested fields |
| 12 | TriggerMessage record purpose | Not explained | Documented: carries ALL context so pipeline handler needs NO DB fetch |
| 13 | MessageHandler Map type | Implied Map<String, String> | Explicitly `Map<String, Object>` |
