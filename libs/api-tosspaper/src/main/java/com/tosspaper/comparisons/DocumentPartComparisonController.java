package com.tosspaper.comparisons;

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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final ExecutorService executor = Executors.newCachedThreadPool();

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
     * Uses SseEmitter for proper flushing in Spring MVC.
     *
     * @param xContextId Company context header
     * @param assignedId Extraction task assigned ID
     * @return SSE stream of comparison events
     */
    @PostMapping(value = "/{assignedId}/", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:edit')")
    public SseEmitter runComparison(
            @RequestHeader("X-Context-Id") String xContextId,
            @PathVariable String assignedId) {

        log.info("POST /v1/comparisons/{}/  (SSE stream)", assignedId);

        // 3 minute timeout for long AI comparisons
        SseEmitter emitter = new SseEmitter(180_000L);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        // Validate extraction task exists and belongs to company
        ExtractionTask task = extractionTaskRepository.findByAssignedId(assignedId);
        if (task == null) {
            log.warn("Extraction task not found: {}", assignedId);
            sendErrorAndComplete(emitter, "NOT_FOUND", "Document not found");
            return emitter;
        }

        if (!task.getCompanyId().equals(companyId)) {
            log.warn("Company mismatch for task: {} (expected={}, actual={})",
                    assignedId, companyId, task.getCompanyId());
            sendErrorAndComplete(emitter, "FORBIDDEN", "Access denied");
            return emitter;
        }

        if (task.getPurchaseOrderId() == null || task.getPurchaseOrderId().isBlank()) {
            log.warn("No PO linked for task: {}", assignedId);
            sendErrorAndComplete(emitter, "NO_PO_LINKED", "No purchase order linked to this document");
            return emitter;
        }

        if (task.getConformedJson() == null || task.getConformedJson().isBlank()) {
            log.warn("Document not conformed: {}", assignedId);
            sendErrorAndComplete(emitter, "NOT_CONFORMED", "Document has not been conformed yet");
            return emitter;
        }

        // Execute comparison in background thread
        executor.execute(() -> {
            try {
                // Send immediate feedback
                sendEvent(emitter, "activity", ComparisonEvent.Activity.processing());

                // Check if service is streaming-capable
                if (service instanceof StreamingDocumentComparisonService streamingService) {
                    executeStreamingComparison(emitter, streamingService, task, companyId, assignedId);
                } else {
                    executeBlockingComparison(emitter, task, companyId);
                }
            } catch (Exception e) {
                log.error("Comparison failed: assignedId={}", assignedId, e);
                sendErrorAndComplete(emitter, "COMPARISON_FAILED", e.getMessage());
            }
        });

        // Handle client disconnect
        emitter.onCompletion(() -> log.debug("SSE completed: assignedId={}", assignedId));
        emitter.onTimeout(() -> log.warn("SSE timeout: assignedId={}", assignedId));
        emitter.onError(e -> log.error("SSE error: assignedId={}", assignedId, e));

        return emitter;
    }

    /**
     * Execute streaming comparison and emit events.
     */
    private void executeStreamingComparison(
            SseEmitter emitter,
            StreamingDocumentComparisonService streamingService,
            ExtractionTask task,
            Long companyId,
            String assignedId) {

        poLookupService.getPoWithItemsByPoNumber(companyId, task.getPoNumber())
                .ifPresentOrElse(
                        po -> {
                            ComparisonContext context = new ComparisonContext(po, task);

                            streamingService.executeComparisonStream(context)
                                    .doOnNext(event -> sendEvent(emitter, event))
                                    .doOnError(error -> {
                                        log.error("Streaming comparison error: assignedId={}", assignedId, error);
                                        sendErrorAndComplete(emitter, "COMPARISON_FAILED", error.getMessage());
                                    })
                                    .doOnComplete(() -> {
                                        log.info("Streaming comparison completed: assignedId={}", assignedId);
                                        emitter.complete();
                                    })
                                    .subscribe();
                        },
                        () -> {
                            log.error("PO not found for task: {} (poNumber={})", assignedId, task.getPoNumber());
                            sendErrorAndComplete(emitter, "PO_NOT_FOUND", "Purchase order not found: " + task.getPoNumber());
                        }
                );
    }

    /**
     * Execute blocking comparison and emit complete event.
     */
    private void executeBlockingComparison(SseEmitter emitter, ExtractionTask task, Long companyId) {
        poLookupService.getPoWithItemsByPoNumber(companyId, task.getPoNumber())
                .ifPresentOrElse(
                        po -> {
                            try {
                                ComparisonContext context = new ComparisonContext(po, task);
                                Comparison result = service.compareDocumentParts(context);
                                sendEvent(emitter, ComparisonEvent.Complete.of(result));
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("Blocking comparison failed", e);
                                sendErrorAndComplete(emitter, "COMPARISON_FAILED", e.getMessage());
                            }
                        },
                        () -> sendErrorAndComplete(emitter, "PO_NOT_FOUND", "Purchase order not found")
                );
    }

    /**
     * Send an event through the SSE emitter.
     */
    private void sendEvent(SseEmitter emitter, ComparisonEvent event) {
        String eventType = switch (event) {
            case ComparisonEvent.Activity a -> "activity";
            case ComparisonEvent.Thinking t -> "thinking";
            case ComparisonEvent.Finding f -> "finding";
            case ComparisonEvent.Complete c -> "complete";
            case ComparisonEvent.Error e -> "error";
        };

        Object data = event instanceof ComparisonEvent.Complete c
                ? mapper.toDto(c.result())
                : event;

        sendEvent(emitter, eventType, data);
    }

    /**
     * Send a typed event through the SSE emitter.
     */
    private void sendEvent(SseEmitter emitter, String eventType, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(json, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }

    /**
     * Send an error event and complete the emitter.
     */
    private void sendErrorAndComplete(SseEmitter emitter, String code, String message) {
        try {
            String json = String.format("{\"message\":\"%s\",\"code\":\"%s\"}",
                    message.replace("\"", "\\\""), code);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(json, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            log.warn("Failed to send error SSE event: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }
}
