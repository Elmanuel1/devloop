package com.tosspaper.aiengine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.advisor.ComparisonAuditAdvisor;
import com.tosspaper.aiengine.loaders.JsonSchemaLoader;
import com.tosspaper.aiengine.tools.FileTools;
import com.tosspaper.aiengine.properties.ComparisonProperties;
import com.tosspaper.aiengine.vfs.VFSContextMapper;
import com.tosspaper.aiengine.vfs.VfsDocumentContext;
import com.tosspaper.aiengine.vfs.VirtualFilesystemService;
import com.tosspaper.aiengine.service.LineItemValidator;
import com.tosspaper.aiengine.service.LineItemValidator.BatchCorrectionResult;
import com.tosspaper.aiengine.service.LineItemValidator.FailedValidation;
import com.tosspaper.aiengine.service.LineItemValidator.ValidationBatch;
import com.tosspaper.aiengine.tools.FileTools.PoItemInfo;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.extraction.dto.ComparisonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming comparison agent using Spring AI ChatClient.
 *
 * <p>This agent replaces the CLI-based FileSystemComparisonAgent with direct API calls.
 * Benefits:
 * <ul>
 *   <li>~1MB per request vs ~500MB for CLI process</li>
 *   <li>100+ concurrent requests per 4GB instance</li>
 *   <li>Real-time SSE streaming of progress</li>
 *   <li>Embedded Java file tools (no IPC overhead)</li>
 * </ul>
 *
 * <p>Workflow:
 * <ol>
 *   <li>Prepare files in VFS (PO, document)</li>
 *   <li>Configure FileTools with working directory</li>
 *   <li>Execute ChatClient with streaming</li>
 *   <li>Emit events for tool calls and thinking</li>
 *   <li>Read results from VFS</li>
 *   <li>Emit Complete event with results</li>
 * </ol>
 *
 * <p>Session isolation: Each comparison request generates a unique comparisonId (UUID)
 * to prevent race conditions when multiple users compare the same document concurrently.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.comparison.streaming.enabled", havingValue = "true")
public class StreamingComparisonAgent {

    /**
     * Internal result holder for passing comparisonId alongside Comparison.
     */
    private record ComparisonWithSession(Comparison comparison, String comparisonId) {}

    private static final String COMPARISON_PROMPT_TEMPLATE = """
        Compare the extracted document against the purchase order. Call out ALL discrepancies.

        ## Files to Read

        1. Read the PO: readFile("po.json")
        2. Find document: listDirectory("%s") then readFile the JSON file

        Document ID: %s
        PO ID: %s
        Document line items: %d (DO NOT create more line_item results than this count)
        %s

        ## CRITICAL: Match Classification Rules

        Every field comparison MUST include:
        - poValue: The exact value from PO (use "Not specified" if empty)
        - documentValue: The exact value from document (use "Not specified" if empty)
        - match: Classification (exact/close/mismatch)
        - explanation: Human-readable explanation

        **Match Classifications - BE STRICT:**

        "exact" - ONLY when values are IDENTICAL or semantically equivalent:
        - "100" vs "100" ✓
        - "Canada" vs "CA" ✓ (standard ISO equivalence)
        - "John Smith" vs "John Smith" ✓

        "close" - Minor formatting/typo differences ONLY:
        - "123 Main St" vs "123 Main Street" (abbreviation)
        - "hi@cursor.com" vs "hii@cursor.com" (typo)
        - "(555) 123-4567" vs "555-123-4567" (phone format)

        "mismatch" - ANY value difference that changes meaning:
        - Different sizes: "Large" vs "Medium" → MISMATCH
        - Different prices: $50.00 vs $55.00 → MISMATCH
        - Different currencies: USD vs CAD → MISMATCH
        - Missing values: present vs "Not specified" → MISMATCH
        - Different names: "Cursor" vs "Anysphere Inc." → MISMATCH (unless proven brand/legal name)

        ## BLOCKING Issues (isBlocking: true)

        - Price differences of any amount
        - Currency mismatches
        - Items not in PO
        - **Oversupply**: document quantity EXCEEDS PO quantity (BLOCKING)

        ## Quantity Rules

        - Document qty < PO qty: "close" (partial delivery is normal, not blocking)
        - Document qty = PO qty: "exact"
        - Document qty > PO qty: "mismatch" + BLOCKING (oversupply NOT allowed)

        ## Comparison Checklist

        For VENDOR and SHIP_TO, compare each field:
        □ Name: exact string match? (brand vs legal = mismatch unless verified)
        □ Street: exact address? unit numbers present?
        □ City/Province/Postal: exact values?
        □ Country: ISO code vs full name (close if equivalent)
        □ Email: exact domain and username?
        □ Phone: present in both? exact number?

        For each LINE_ITEM - BE VERY STRICT:
        □ Description: exact text? size/variant differences = MISMATCH
        □ Quantity: see Quantity Rules above (oversupply = BLOCKING)
        □ Unit price: exact amount? any difference = MISMATCH + BLOCKING
        □ Currency: same currency? different = MISMATCH + BLOCKING
        □ Item code: matches if present?

        ## LINE ITEM MATCHING (0-based indexing)

        Use 0-based indexing matching JSON array indices (first item = 0).

        MATCHING CRITERIA - ALL must align:
        1. Item Code: MUST match exactly
        2. Description: MUST be the same product type (not just similar text)
        3. Each PO line can only match ONE document line

        STRICT RULE: Same item code but DIFFERENT product descriptions = NOT a match
        Example: "MONOBASE" vs "HOOP STEEL RISER" are different products even with same item code.

        MATCHING WORKFLOW:
        1. Read po.json and note the total item count (N). Valid indices are 0 to N-1.
        2. For each document line item (docIndex = 0, 1, 2...):
           a. Find the PO item with matching itemCode AND same product description
           b. Set poIndex to the matched PO item index (0-based)
           c. If no match found, set poIndex = null (item not in PO = BLOCKING)

        IMPORTANT: The system will validate your matches afterwards and correct if needed.
        IMPORTANT: Indices must be 0 to N-1 (where N = number of items).

        After reading files, respond with ONLY valid JSON. Start with { end with }
        """;

    private final ChatClient chatClient;
    private final FileTools fileTools;
    private final VirtualFilesystemService vfsService;
    private final VFSContextMapper contextMapper;
    private final ObjectMapper objectMapper;
    private final JsonSchemaLoader schemaLoader;
    private final ActivityMapper activityMapper;
    private final ComparisonProperties properties;
    private final LineItemValidator lineItemValidator;

    public StreamingComparisonAgent(
            @Qualifier("comparisonChatClient") ChatClient chatClient,
            FileTools fileTools,
            VirtualFilesystemService vfsService,
            VFSContextMapper contextMapper,
            ObjectMapper objectMapper,
            JsonSchemaLoader schemaLoader,
            ActivityMapper activityMapper,
            ComparisonProperties properties,
            LineItemValidator lineItemValidator) {
        this.chatClient = chatClient;
        this.fileTools = fileTools;
        this.vfsService = vfsService;
        this.contextMapper = contextMapper;
        this.objectMapper = objectMapper;
        this.schemaLoader = schemaLoader;
        this.activityMapper = activityMapper;
        this.properties = properties;
        this.lineItemValidator = lineItemValidator;
    }

    /**
     * Execute document comparison blocking (no streaming).
     * Use this for backend calls where streaming events are not needed.
     *
     * @param context Comparison context containing PO and extraction task
     * @return Comparison result
     */
    public Comparison executeComparisonBlocking(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();

        log.info("Starting blocking comparison: company={}, document={}, po={}",
                task.getCompanyId(), task.getAssignedId(), task.getPoNumber());

        // Use a no-op sink - events are discarded
        Sinks.Many<ComparisonEvent> noOpSink = Sinks.many().multicast().directBestEffort();

        ComparisonWithSession result = executeComparisonAsync(context, noOpSink)
                .block(Duration.ofSeconds(properties.getStreaming().getTimeoutSeconds()));

        return result != null ? result.comparison() : null;
    }

    /**
     * Execute document comparison with streaming events.
     *
     * @param context Comparison context containing PO and extraction task
     * @return Flux of ComparisonEvents for SSE streaming
     */
    public Flux<ComparisonEvent> executeComparison(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();
        PurchaseOrder po = context.purchaseOrder();

        log.info("Starting streaming comparison: company={}, document={}, po={}",
                task.getCompanyId(), task.getAssignedId(), task.getPoNumber());

        // Create a sink for emitting events
        Sinks.Many<ComparisonEvent> sink = Sinks.many().multicast().onBackpressureBuffer();

        // Execute comparison on a separate thread so events can be delivered immediately
        executeComparisonAsync(context, sink)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> {
                    log.error("Streaming comparison failed", error);
                    sink.tryEmitNext(ComparisonEvent.Error.of(error.getMessage()));
                    sink.tryEmitComplete();
                })
                .doOnSuccess(result -> {
                    log.info("Streaming comparison completed successfully: comparisonId={}", result.comparisonId());
                    var emitResult = sink.tryEmitNext(ComparisonEvent.Complete.of(result.comparison(), result.comparisonId()));
                    if (emitResult.isFailure()) {
                        log.error("Failed to emit Complete event: {}", emitResult);
                    }
                    sink.tryEmitComplete();
                })
                .subscribe();

        return sink.asFlux()
                .timeout(Duration.ofSeconds(properties.getStreaming().getTimeoutSeconds()));
    }

    /**
     * Execute the comparison asynchronously.
     * Uses Spring AI structured output to enforce the Comparison schema.
     *
     * @return Mono containing comparison result with session ID for isolation tracking
     */
    private Mono<ComparisonWithSession> executeComparisonAsync(ComparisonContext context, Sinks.Many<ComparisonEvent> sink) {
        return Mono.fromCallable(() -> {
            ExtractionTask task = context.extractionTask();
            PurchaseOrder po = context.purchaseOrder();

            // 1. Get comparisonId from context (generated by controller) or generate fallback
            String comparisonId = context.comparisonId();
            if (comparisonId == null || comparisonId.isBlank()) {
                comparisonId = task.getAssignedId() + "-" + UUID.randomUUID().toString().substring(0, 8);
                log.info("Generated fallback comparisonId: {}", comparisonId);
            } else {
                log.info("Using comparisonId from context: {}", comparisonId);
            }

            // 2. Prepare files in VFS
            sink.tryEmitNext(new ComparisonEvent.Activity("📁", "Preparing files..."));
            Path workingDir = prepareFiles(context);

            // 3. Create session-specific output directory for isolation
            Path sessionDir = workingDir.resolve("comparisons").resolve(comparisonId);
            log.debug("Session output directory: {}", sessionDir);

            // 4. Configure FileTools with working directory (for reading PO/docs)
            fileTools.setWorkingDirectory(workingDir);

            // 5. Build the prompt
            String prompt = buildPrompt(context, workingDir);

            // 6. Execute ChatClient and get response
            sink.tryEmitNext(new ComparisonEvent.Activity("🤖", "Starting AI analysis..."));

            log.debug("Executing ChatClient with prompt length: {}", prompt.length());

            // Start keepalive scheduler - sends activity events every 30s to prevent ALB timeout
            AtomicBoolean aiComplete = new AtomicBoolean(false);
            String rawJson;

            try (ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor()) {
                keepalive.scheduleAtFixedRate(() -> {
                    if (!aiComplete.get()) {
                        log.debug("Sending keepalive ping during AI analysis");
                        sink.tryEmitNext(new ComparisonEvent.Activity("⏳", "Waiting for comparison report..."));
                    }
                }, 10, 10, TimeUnit.SECONDS);

                // Get raw content and normalize enum values (AI returns MATCHED, schema expects matched)
                // Pass session directory to advisor for audit logging (isolated per comparison)
                // BLOCKING AI CALL (60-90s)
                rawJson = chatClient.prompt()
                        .user(prompt)
                        .advisors(advisor -> advisor.param(ComparisonAuditAdvisor.WORKING_DIR_KEY, sessionDir))
                        .call()
                        .content();

                aiComplete.set(true);
            }

            // Extract JSON from response (may be wrapped in markdown code blocks)
            String json = extractJson(rawJson);

            // Normalize enum values to lowercase
            json = normalizeEnumValues(json);

            log.debug("Normalized JSON response length: {}", json.length());

            // Parse the normalized JSON
            Comparison comparison = objectMapper.readValue(json, Comparison.class);

            log.debug("ChatClient response received with structured output");

            // 7. POST-HOC VALIDATION: Strip phantoms + validate and correct line item matches
            int docLineItemCount = countLineItems(task);
            sink.tryEmitNext(new ComparisonEvent.Activity("🔍", "Validating line items..."));
            comparison = validateAndCorrectLineItems(comparison, sessionDir, sink, docLineItemCount);

            // 8. Save results to session directory for isolation
            sink.tryEmitNext(new ComparisonEvent.Activity("📊", "Saving results..."));
            Path resultsPath = sessionDir.resolve("_results.json");
            String resultJson = objectMapper.writeValueAsString(comparison);
            vfsService.writeFile(resultsPath, resultJson);

            log.info("Comparison complete: comparisonId={}, documentId={}, resultCount={}",
                    comparisonId,
                    comparison.getDocumentId(),
                    comparison.getResults() != null ? comparison.getResults().size() : 0);

            return new ComparisonWithSession(comparison, comparisonId);
        }).doFinally(signal -> {
            // Clean up ThreadLocal state to prevent memory leaks
            fileTools.clearThreadLocalState();
        });
    }

    /**
     * Prepare files in VFS for comparison.
     * PO and document files are saved to shared working directory.
     * Session-specific outputs (results, audits) go to separate session directory.
     *
     * @param context Comparison context
     * @return Working directory path (shared for PO/docs)
     */
    private Path prepareFiles(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();
        PurchaseOrder po = context.purchaseOrder();

        // Get working directory for PO and documents (shared, read-only during comparison)
        Path workingDir = vfsService.getWorkingDirectory(task.getCompanyId(), task.getPoNumber());

        // Save PO to VFS (idempotent - same content overwrites safely)
        VfsDocumentContext poContext = contextMapper.from(po);
        vfsService.save(poContext);
        log.debug("Saved PO to VFS: {}", vfsService.getPath(poContext));

        // Set PO item count for index validation in FileTools
        int poItemCount = po.getItems() != null ? po.getItems().size() : 0;
        fileTools.setPoItemCount(poItemCount);
        log.debug("Set PO item count to {} (valid indices: 0 to {})", poItemCount, poItemCount - 1);

        // Save document to VFS (idempotent)
        VfsDocumentContext docContext = contextMapper.from(task);
        vfsService.save(docContext);
        log.debug("Saved document to VFS: {}", vfsService.getPath(docContext));

        // Copy comparison schema to _schema folder for AI to read
        try {
            Path schemaPath = workingDir.resolve("_schema/comparison.json");
            String schemaContent = schemaLoader.loadSchema("comparison");
            vfsService.writeFile(schemaPath, schemaContent);
            log.debug("Saved comparison schema to: {}", schemaPath);
        } catch (Exception e) {
            log.warn("Failed to save comparison schema, AI will use embedded instructions", e);
        }

        return workingDir;
    }

    /**
     * Build the comparison prompt - tells AI where to find files (AI uses tools to read them).
     */
    private String buildPrompt(ComparisonContext context, Path workingDir) {
        ExtractionTask task = context.extractionTask();
        PurchaseOrder po = context.purchaseOrder();

        // Get document type folder name (invoice, delivery_slip, delivery_note)
        String docTypeFolder = task.getDocumentType().name().toLowerCase();

        // Build currency context line — use PO currency, fall back to vendor currency
        String currencyLine = Optional.ofNullable(po.getCurrencyCode())
                .or(() -> Optional.ofNullable(po.getVendorContact()).map(Party::getCurrencyCode))
                .map(c -> "PO Currency: " + c.getCode()
                        + " — All prices in this order are expected in " + c.getCode()
                        + ". Flag any document line item or total in a different currency as MISMATCH + BLOCKING.")
                .orElse("");

        int docLineItemCount = countLineItems(task);

        return String.format(COMPARISON_PROMPT_TEMPLATE,
                docTypeFolder,
                task.getAssignedId(),
                task.getPoNumber(),
                docLineItemCount,
                currencyLine
        );
    }

    /**
     * Count line items in the extraction task.
     * Parses the conformed JSON and looks for lineItems, line_items, or items fields.
     */
    private int countLineItems(ExtractionTask task) {
        String conformedJson = task.getConformedJson();
        if (conformedJson == null || conformedJson.isBlank()) {
            return 0;
        }

        try {
            JsonNode root = objectMapper.readTree(conformedJson);

            // Try different field names for line items
            JsonNode lineItems = root.path("lineItems");
            if (lineItems.isArray()) {
                return lineItems.size();
            }

            lineItems = root.path("line_items");
            if (lineItems.isArray()) {
                return lineItems.size();
            }

            lineItems = root.path("items");
            if (lineItems.isArray()) {
                return lineItems.size();
            }

            // Count charges across all deliveryTransactions
            JsonNode transactions = root.path("deliveryTransactions");
            if (transactions.isArray()) {
                int total = 0;
                for (JsonNode txn : transactions) {
                    JsonNode charges = txn.path("charges");
                    if (charges.isArray()) {
                        total += charges.size();
                    }
                }
                if (total > 0) return total;
            }

            return 0;
        } catch (Exception e) {
            log.warn("Failed to parse conformed JSON for line item count", e);
            return 0;
        }
    }

    /**
     * Extract JSON from AI response that may be wrapped in markdown code blocks.
     * Handles responses like: ```json\n{...}\n``` or just raw JSON.
     */
    private String extractJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            throw new ComparisonAgentException("Empty response from AI", null);
        }

        String trimmed = rawResponse.trim();

        // Check for markdown code blocks
        if (trimmed.startsWith("```")) {
            // Find the end of the opening fence (```json or just ```)
            int start = trimmed.indexOf('\n');
            if (start == -1) {
                throw new ComparisonAgentException("Malformed markdown code block", null);
            }

            // Find the closing fence
            int end = trimmed.lastIndexOf("```");
            if (end <= start) {
                throw new ComparisonAgentException("Unclosed markdown code block", null);
            }

            return trimmed.substring(start + 1, end).trim();
        }

        // If it starts with { or [, assume it's raw JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        // Try to find JSON object in the response
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }

        throw new ComparisonAgentException("Could not extract JSON from response: " + trimmed.substring(0, Math.min(100, trimmed.length())), null);
    }

    /**
     * Normalize enum values in JSON to lowercase.
     * AI models often return MATCHED, PARTIAL, UNMATCHED but schema expects lowercase.
     */
    private String normalizeEnumValues(String json) {
        // Normalize status values
        String normalized = json
                .replace("\"MATCHED\"", "\"matched\"")
                .replace("\"PARTIAL\"", "\"partial\"")
                .replace("\"UNMATCHED\"", "\"unmatched\"");

        // Normalize severity values
        normalized = normalized
                .replace("\"INFO\"", "\"info\"")
                .replace("\"WARNING\"", "\"warning\"")
                .replace("\"BLOCKING\"", "\"blocking\"");

        // Normalize result type values
        normalized = normalized
                .replace("\"VENDOR\"", "\"vendor\"")
                .replace("\"SHIP_TO\"", "\"ship_to\"")
                .replace("\"LINE_ITEM\"", "\"line_item\"");

        // Normalize match field values (exact, close, mismatch)
        normalized = normalized
                .replace("\"EXACT\"", "\"exact\"")
                .replace("\"CLOSE\"", "\"close\"")
                .replace("\"MISMATCH\"", "\"mismatch\"");

        return normalized;
    }

    /**
     * Validate all line items and correct any invalid matches.
     *
     * <p>Post-hoc validation flow:
     * <ol>
     *   <li>Validate all line_items from the comparison</li>
     *   <li>For each failed item, use AI to find the correct poIndex</li>
     *   <li>Apply corrections to the comparison</li>
     * </ol>
     *
     * @param comparison The comparison result from AI
     * @param workingDir Working directory containing po.json
     * @param sink Event sink for progress updates
     * @return Updated comparison with corrected poIndex values
     */
    private Comparison validateAndCorrectLineItems(
            Comparison comparison,
            Path workingDir,
            Sinks.Many<ComparisonEvent> sink,
            int docLineItemCount) {

        log.info("========================================");
        log.info("=== LINE ITEM VALIDATION PHASE START ===");
        log.info("========================================");

        // 1. Validate all line_items (also strips phantom items beyond docLineItemCount)
        ValidationBatch batch = lineItemValidator.validateLineItems(comparison, docLineItemCount);

        log.info("=== VALIDATION SUMMARY === valid={} failed={} usedIndices={}",
                batch.validated().size(), batch.failed().size(), batch.usedPoIndices());

        if (batch.failed().isEmpty()) {
            log.info("=== ALL LINE ITEMS VALID - NO CORRECTIONS NEEDED ===");
            return comparison;
        }

        log.info("========================================");
        log.info("=== BATCH CORRECTION PHASE START === {} items to correct", batch.failed().size());
        log.info("========================================");

        sink.tryEmitNext(new ComparisonEvent.Activity("🔧",
                "Correcting " + batch.failed().size() + " line items in batch..."));

        // 2. Get available PO items (not yet matched)
        Set<Integer> usedPoIndices = new HashSet<>(batch.usedPoIndices());
        List<PoItemInfo> availablePoItems = lineItemValidator.getAvailablePoItems(usedPoIndices);
        log.info("Available PO items for correction: {}", availablePoItems.size());

        // 3. Batch correct all failed items in ONE AI call
        BatchCorrectionResult batchResult = lineItemValidator.correctFailedItemsBatch(
                batch.failed(), usedPoIndices, availablePoItems);

        // 4. Build corrections map with corrected results
        Map<Integer, ComparisonResult> corrections = new HashMap<>();

        // Apply successful corrections
        for (Map.Entry<Integer, Integer> entry : batchResult.corrections().entrySet()) {
            int docIndex = entry.getKey();
            int correctedPoIndex = entry.getValue();

            // Find the original result to update
            for (FailedValidation failed : batch.failed()) {
                if (failed.validation().docIndex() == docIndex) {
                    ComparisonResult correctedResult = failed.originalResult();
                    correctedResult.setPoIndex((long) correctedPoIndex);
                    corrections.put(docIndex, correctedResult);
                    log.info("=== CORRECTION APPLIED === docIndex={} → poIndex={}", docIndex, correctedPoIndex);
                    break;
                }
            }
        }

        // Mark uncorrectable items as unmatched
        for (Integer docIndex : batchResult.uncorrectable()) {
            for (FailedValidation failed : batch.failed()) {
                if (failed.validation().docIndex() == docIndex) {
                    ComparisonResult unmatchedResult = failed.originalResult();
                    unmatchedResult.setPoIndex(null);
                    unmatchedResult.setStatus(ComparisonResult.Status.UNMATCHED);
                    unmatchedResult.setSeverity(ComparisonResult.Severity.BLOCKING);
                    corrections.put(docIndex, unmatchedResult);
                    log.error("=== MARKED UNMATCHED === docIndex={}", docIndex);
                    break;
                }
            }
        }

        log.info("========================================");
        log.info("=== BATCH CORRECTION COMPLETE === corrected={} unmatched={}",
                batchResult.corrections().size(), batchResult.uncorrectable().size());
        log.info("========================================");

        // 3. Apply corrections - replace failed results with corrected ones
        return applyCorrections(comparison, corrections);
    }

    /**
     * Apply corrections to the comparison result.
     *
     * @param comparison Original comparison
     * @param corrections Map of docIndex to corrected ComparisonResult
     * @return Updated comparison with corrections applied
     */
    private Comparison applyCorrections(Comparison comparison, Map<Integer, ComparisonResult> corrections) {
        if (corrections.isEmpty()) {
            return comparison;
        }

        List<ComparisonResult> results = new ArrayList<>(comparison.getResults());

        for (int i = 0; i < results.size(); i++) {
            ComparisonResult result = results.get(i);
            if (result.getType() == null || !"line_item".equals(result.getType().value())) {
                continue;
            }

            Long extractedIndex = result.getExtractedIndex();
            if (extractedIndex == null) {
                continue;
            }

            int docIndex = extractedIndex.intValue();
            if (corrections.containsKey(docIndex)) {
                // Replace with corrected result
                log.debug("Applying correction for docIndex={}", docIndex);
                results.set(i, corrections.get(docIndex));
            }
        }

        comparison.setResults(results);

        // Recalculate blocking issues count
        long blockingCount = results.stream()
                .filter(r -> r.getSeverity() == ComparisonResult.Severity.BLOCKING)
                .count();
        comparison.setBlockingIssues(blockingCount);

        return comparison;
    }

    /**
     * Exception thrown when streaming comparison fails.
     */
    public static class ComparisonAgentException extends RuntimeException {
        public ComparisonAgentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
