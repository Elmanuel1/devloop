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
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.extraction.dto.Comparison;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.time.Duration;

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
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.comparison.streaming.enabled", havingValue = "true")
public class StreamingComparisonAgent {

    private static final String COMPARISON_PROMPT_TEMPLATE = """
        Compare the extracted document against the purchase order. Call out ALL discrepancies.

        ## Files to Read

        1. Read the PO: readFile("po.json")
        2. Find document: listDirectory("%s") then readFile the JSON file

        Document ID: %s
        PO ID: %s

        ## Gradual Comparison Checklist

        For VENDOR and SHIP_TO, check each field:
        □ Name: exact match? brand vs legal name? person vs org?
        □ Street: unit number? road designation? formatting?
        □ City/Province/Postal: exact or normalized match?
        □ Country: ISO code vs full name?
        □ Email: domain match? username typos?
        □ Phone: present in both? format differences?

        For each LINE_ITEM:
        □ Description: exact text match?
        □ Quantity: same value?
        □ Unit price: same amount? same currency?
        □ Item code: matches if present?

        ## Discrepancy Examples

        Report differences like:
        - "Cursor" vs "Anysphere Inc." → brand vs legal name
        - "654 cook" vs "434-654 Cook Rd" → missing unit number 434
        - "hi@cursor.com" vs "hii@cursor.com" → typo (extra 'i')
        - Phone in document but not in PO → field present/missing

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

    public StreamingComparisonAgent(
            @Qualifier("comparisonChatClient") ChatClient chatClient,
            FileTools fileTools,
            VirtualFilesystemService vfsService,
            VFSContextMapper contextMapper,
            ObjectMapper objectMapper,
            JsonSchemaLoader schemaLoader,
            ActivityMapper activityMapper,
            ComparisonProperties properties) {
        this.chatClient = chatClient;
        this.fileTools = fileTools;
        this.vfsService = vfsService;
        this.contextMapper = contextMapper;
        this.objectMapper = objectMapper;
        this.schemaLoader = schemaLoader;
        this.activityMapper = activityMapper;
        this.properties = properties;
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

        // Execute comparison in background
        executeComparisonAsync(context, sink)
                .doOnError(error -> {
                    log.error("Streaming comparison failed", error);
                    sink.tryEmitNext(ComparisonEvent.Error.of(error.getMessage()));
                    sink.tryEmitComplete();
                })
                .doOnSuccess(comparison -> {
                    log.info("Streaming comparison completed successfully");
                    sink.tryEmitNext(ComparisonEvent.Complete.of(comparison));
                    sink.tryEmitComplete();
                })
                .subscribe();

        return sink.asFlux()
                .timeout(Duration.ofSeconds(properties.getStreaming().getTimeoutSeconds()));
    }

    /**
     * Execute the comparison asynchronously.
     * Uses Spring AI structured output to enforce the Comparison schema.
     */
    private Mono<Comparison> executeComparisonAsync(ComparisonContext context, Sinks.Many<ComparisonEvent> sink) {
        return Mono.fromCallable(() -> {
            ExtractionTask task = context.extractionTask();
            PurchaseOrder po = context.purchaseOrder();

            // 1. Prepare files in VFS
            sink.tryEmitNext(new ComparisonEvent.Activity("📁", "Preparing files..."));
            Path workingDir = prepareFiles(context);

            // 2. Configure FileTools with working directory
            fileTools.setWorkingDirectory(workingDir);

            // 3. Build the prompt
            String prompt = buildPrompt(context, workingDir);

            // 4. Execute ChatClient and get response
            sink.tryEmitNext(new ComparisonEvent.Activity("🤖", "Starting AI analysis..."));

            log.debug("Executing ChatClient with prompt length: {}", prompt.length());

            // Get raw content and normalize enum values (AI returns MATCHED, schema expects matched)
            // Pass working directory to advisor for audit logging
            String rawJson = chatClient.prompt()
                    .user(prompt)
                    .advisors(advisor -> advisor.param(ComparisonAuditAdvisor.WORKING_DIR_KEY, workingDir))
                    .call()
                    .content();

            // Extract JSON from response (may be wrapped in markdown code blocks)
            String json = extractJson(rawJson);

            // Normalize enum values to lowercase
            json = normalizeEnumValues(json);

            log.debug("Normalized JSON response length: {}", json.length());

            // Parse the normalized JSON
            Comparison comparison = objectMapper.readValue(json, Comparison.class);

            log.debug("ChatClient response received with structured output");

            // 5. Save results to VFS for audit/debugging
            sink.tryEmitNext(new ComparisonEvent.Activity("📊", "Saving results..."));
            Path resultsPath = workingDir.resolve("_results.json");
            String resultJson = objectMapper.writeValueAsString(comparison);
            vfsService.writeFile(resultsPath, resultJson);

            log.info("Comparison complete: documentId={}, resultCount={}",
                    comparison.getDocumentId(),
                    comparison.getResults() != null ? comparison.getResults().size() : 0);

            return comparison;
        });
    }

    /**
     * Prepare files in VFS for comparison.
     *
     * @param context Comparison context
     * @return Working directory path
     */
    private Path prepareFiles(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();
        PurchaseOrder po = context.purchaseOrder();

        // Get working directory
        Path workingDir = vfsService.getWorkingDirectory(task.getCompanyId(), task.getPoNumber());

        // Save PO to VFS
        VfsDocumentContext poContext = contextMapper.from(po);
        vfsService.save(poContext);
        log.debug("Saved PO to VFS: {}", vfsService.getPath(poContext));

        // Save document to VFS
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

        // Get document type folder name (invoice, delivery_slip, delivery_note)
        String docTypeFolder = task.getDocumentType().name().toLowerCase();

        return String.format(COMPARISON_PROMPT_TEMPLATE,
                docTypeFolder,
                task.getAssignedId(),
                task.getPoNumber()
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

        return normalized;
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
