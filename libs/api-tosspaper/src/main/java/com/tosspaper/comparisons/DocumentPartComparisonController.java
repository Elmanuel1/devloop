package com.tosspaper.comparisons;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.agent.ComparisonEvent;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.aiengine.service.impl.StreamingDocumentComparisonService;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.model.DocumentComparisonResult;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.service.DocumentPartComparisonService;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * Controller for document part comparison operations.
 *
 * <p>Provides both streaming (SSE) and non-streaming endpoints for document comparison.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>GET /v1/comparisons - Retrieve existing comparison results</li>
 *   <li>POST /v1/comparisons/{assignedId}/ - Run comparison with SSE progress stream</li>
 * </ul>
 *
 * <h2>SSE Event Types</h2>
 * <ul>
 *   <li>activity - User-friendly progress messages</li>
 *   <li>thinking - AI reasoning (Claude extended thinking)</li>
 *   <li>finding - Individual comparison findings</li>
 *   <li>complete - Final result with summary</li>
 *   <li>error - Error information</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/v1/comparisons")
@RequiredArgsConstructor
public class DocumentPartComparisonController {

    private final DocumentPartComparisonService service;
    private final DocumentComparisonMapper mapper;
    private final ExtractionTaskRepository extractionTaskRepository;
    private final PurchaseOrderLookupService poLookupService;
    private final ObjectMapper objectMapper;

    /**
     * Get comparison results for a document.
     */
    @GetMapping
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:view')")
    public ResponseEntity<DocumentComparisonResult> getComparisons(
            @RequestHeader("X-Context-Id") String xContextId,
            @RequestParam String assignedId) {

        log.debug("GET /v1/comparisons?assignedId={}", assignedId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        return service.getComparisonByAssignedId(assignedId, companyId)
                .map(comparison -> {
                    log.debug("Found comparison for assignedId: {}, resultCount: {}",
                            assignedId, comparison.getResults() != null ? comparison.getResults().size() : 0);
                    return ResponseEntity.ok(mapper.toDto(comparison));
                })
                .orElseGet(() -> {
                    log.debug("No comparison found for assignedId: {}", assignedId);
                    return ResponseEntity.notFound().build();
                });
    }

    /**
     * Run comparison with SSE streaming progress.
     *
     * <p>Returns a Server-Sent Events stream with real-time progress updates.
     * The comparison runs synchronously on the server, but progress events are
     * streamed to provide feedback during the ~10-30 second operation.
     *
     * <p>If the client disconnects, the comparison continues running and the
     * result is saved to the database. The client can fetch results later via GET.
     *
     * @param xContextId Company context header
     * @param assignedId Extraction task assigned ID
     * @return SSE stream of comparison events
     */
    @PostMapping(value = "/{assignedId}/", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:edit')")
    public Flux<ServerSentEvent<String>> runComparison(
            @RequestHeader("X-Context-Id") String xContextId,
            @PathVariable String assignedId) {

        log.info("POST /v1/comparisons/{}/  (SSE stream)", assignedId);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        // Validate extraction task exists and belongs to company
        ExtractionTask task = extractionTaskRepository.findByAssignedId(assignedId);
        if (task == null) {
            log.warn("Extraction task not found: {}", assignedId);
            return errorStream("NOT_FOUND", "Document not found");
        }

        if (!task.getCompanyId().equals(companyId)) {
            log.warn("Company mismatch for task: {} (expected={}, actual={})",
                    assignedId, companyId, task.getCompanyId());
            return errorStream("FORBIDDEN", "Access denied");
        }

        if (task.getPurchaseOrderId() == null || task.getPurchaseOrderId().isBlank()) {
            log.warn("No PO linked for task: {}", assignedId);
            return errorStream("NO_PO_LINKED", "No purchase order linked to this document");
        }

        if (task.getConformedJson() == null || task.getConformedJson().isBlank()) {
            log.warn("Document not conformed: {}", assignedId);
            return errorStream("NOT_CONFORMED", "Document has not been conformed yet");
        }

        // Check if service is streaming-capable
        if (!(service instanceof StreamingDocumentComparisonService streamingService)) {
            log.info("Streaming not enabled, falling back to blocking comparison");
            return executeBlockingComparison(task, companyId);
        }

        // Look up PO and execute streaming comparison
        return poLookupService.getPoWithItemsByPoNumber(companyId, task.getPoNumber())
                .map(po -> {
                    ComparisonContext context = new ComparisonContext(po, task);

                    return Flux.concat(
                            // Immediate feedback
                            Flux.just(toServerSentEvent(
                                    ComparisonEvent.Activity.processing())),

                            // Stream comparison events
                            streamingService.executeComparisonStream(context)
                                    .map(this::toServerSentEvent)
                                    .doOnError(error -> log.error(
                                            "Streaming comparison error: assignedId={}",
                                            assignedId, error))
                                    .doOnComplete(() -> log.info(
                                            "Streaming comparison completed: assignedId={}",
                                            assignedId))
                    );
                })
                .orElseGet(() -> {
                    log.error("PO not found for task: {} (poNumber={})", assignedId, task.getPoNumber());
                    return errorStream("PO_NOT_FOUND", "Purchase order not found: " + task.getPoNumber());
                });
    }

    /**
     * Fallback for non-streaming service: run blocking comparison and emit complete event.
     */
    private Flux<ServerSentEvent<String>> executeBlockingComparison(
            ExtractionTask task, Long companyId) {

        return poLookupService.getPoWithItemsByPoNumber(companyId, task.getPoNumber())
                .map(po -> {
                    ComparisonContext context = new ComparisonContext(po, task);

                    return Flux.concat(
                            Flux.just(toServerSentEvent(
                                    ComparisonEvent.Activity.processing())),

                            Flux.<ComparisonEvent>create(sink -> {
                                try {
                                    Comparison result = service.compareDocumentParts(context);
                                    sink.next(ComparisonEvent.Complete.of(result));
                                    sink.complete();
                                } catch (Exception e) {
                                    log.error("Blocking comparison failed", e);
                                    sink.next(ComparisonEvent.Error.of(e.getMessage()));
                                    sink.complete();
                                }
                            }).map(this::toServerSentEvent)
                    );
                })
                .orElseGet(() -> errorStream("PO_NOT_FOUND", "Purchase order not found"));
    }

    /**
     * Create an error SSE stream.
     */
    private Flux<ServerSentEvent<String>> errorStream(String code, String message) {
        String json = String.format("{\"message\":\"%s\",\"code\":\"%s\"}",
                message.replace("\"", "\\\""), code);
        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data(json)
                .build());
    }

    /**
     * Convert a ComparisonEvent to a ServerSentEvent with the appropriate event type.
     * For Complete events, sends JSON string of DocumentComparisonResult (same as GET endpoint).
     */
    private ServerSentEvent<String> toServerSentEvent(ComparisonEvent event) {
        try {
            return switch (event) {
                case ComparisonEvent.Activity a -> ServerSentEvent.<String>builder()
                        .event("activity")
                        .data(objectMapper.writeValueAsString(a))
                        .build();
                case ComparisonEvent.Thinking t -> ServerSentEvent.<String>builder()
                        .event("thinking")
                        .data(objectMapper.writeValueAsString(t))
                        .build();
                case ComparisonEvent.Finding f -> ServerSentEvent.<String>builder()
                        .event("finding")
                        .data(objectMapper.writeValueAsString(f))
                        .build();
                case ComparisonEvent.Complete c -> ServerSentEvent.<String>builder()
                        .event("complete")
                        .data(objectMapper.writeValueAsString(mapper.toDto(c.result())))
                        .build();
                case ComparisonEvent.Error e -> ServerSentEvent.<String>builder()
                        .event("error")
                        .data(objectMapper.writeValueAsString(e))
                        .build();
            };
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE event", e);
            return ServerSentEvent.<String>builder()
                    .event("error")
                    .data("{\"message\":\"Serialization error\",\"code\":\"SERIALIZATION_ERROR\"}")
                    .build();
        }
    }
}
