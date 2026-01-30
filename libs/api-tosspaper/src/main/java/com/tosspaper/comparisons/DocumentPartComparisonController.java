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
import java.util.concurrent.atomic.AtomicBoolean;

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
        AtomicBoolean emitterActive = new AtomicBoolean(true);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        // Validate extraction task exists and belongs to company
        ExtractionTask task = extractionTaskRepository.findByAssignedId(assignedId);
        if (task == null) {
            log.warn("Extraction task not found: {}", assignedId);
            sendErrorAndComplete(emitter, emitterActive, "NOT_FOUND", "Document not found");
            return emitter;
        }

        if (!task.getCompanyId().equals(companyId)) {
            log.warn("Company mismatch for task: {} (expected={}, actual={})",
                    assignedId, companyId, task.getCompanyId());
            sendErrorAndComplete(emitter, emitterActive, "FORBIDDEN", "Access denied");
            return emitter;
        }

        if (task.getPurchaseOrderId() == null || task.getPurchaseOrderId().isBlank()) {
            log.warn("No PO linked for task: {}", assignedId);
            sendErrorAndComplete(emitter, emitterActive, "NO_PO_LINKED", "No purchase order linked to this document");
            return emitter;
        }

        if (task.getConformedJson() == null || task.getConformedJson().isBlank()) {
            log.warn("Document not conformed: {}", assignedId);
            sendErrorAndComplete(emitter, emitterActive, "NOT_CONFORMED", "Document has not been conformed yet");
            return emitter;
        }

        // Execute comparison in background thread
        executor.execute(() -> {
            try {
                // Send immediate feedback
                sendEvent(emitter, emitterActive, "activity", ComparisonEvent.Activity.processing());

                // Check if service is streaming-capable
                if (service instanceof StreamingDocumentComparisonService streamingService) {
                    executeStreamingComparison(emitter, emitterActive, streamingService, task, companyId, assignedId);
                } else {
                    executeBlockingComparison(emitter, emitterActive, task, companyId);
                }
            } catch (Exception e) {
                log.error("Comparison failed: assignedId={}", assignedId, e);
                sendErrorAndComplete(emitter, emitterActive, "COMPARISON_FAILED", e.getMessage());
            }
        });

        // Handle client disconnect - mark emitter as inactive
        emitter.onCompletion(() -> {
            emitterActive.set(false);
            log.debug("SSE completed: assignedId={}", assignedId);
        });
        emitter.onTimeout(() -> {
            emitterActive.set(false);
            log.warn("SSE timeout: assignedId={}", assignedId);
        });
        emitter.onError(e -> {
            emitterActive.set(false);
            log.debug("SSE client disconnected: assignedId={}", assignedId);
        });

        return emitter;
    }

    /**
     * Execute streaming comparison and emit events.
     */
    private void executeStreamingComparison(
            SseEmitter emitter,
            AtomicBoolean emitterActive,
            StreamingDocumentComparisonService streamingService,
            ExtractionTask task,
            Long companyId,
            String assignedId) {

        poLookupService.getPoWithItemsByPoNumber(companyId, task.getPoNumber())
                .ifPresentOrElse(
                        po -> {
                            ComparisonContext context = new ComparisonContext(po, task);

                            streamingService.executeComparisonStream(context)
                                    .doOnNext(event -> sendEvent(emitter, emitterActive, event))
                                    .doOnError(error -> {
                                        log.error("Streaming comparison error: assignedId={}", assignedId, error);
                                        sendErrorAndComplete(emitter, emitterActive, "COMPARISON_FAILED", error.getMessage());
                                    })
                                    .doOnComplete(() -> {
                                        log.info("Streaming comparison completed: assignedId={}", assignedId);
                                        completeEmitter(emitter, emitterActive);
                                    })
                                    .subscribe();
                        },
                        () -> {
                            log.error("PO not found for task: {} (poNumber={})", assignedId, task.getPoNumber());
                            sendErrorAndComplete(emitter, emitterActive, "PO_NOT_FOUND", "Purchase order not found: " + task.getPoNumber());
                        }
                );
    }

    /**
     * Execute blocking comparison and emit complete event.
     */
    private void executeBlockingComparison(SseEmitter emitter, AtomicBoolean emitterActive, ExtractionTask task, Long companyId) {
        poLookupService.getPoWithItemsByPoNumber(companyId, task.getPoNumber())
                .ifPresentOrElse(
                        po -> {
                            try {
                                ComparisonContext context = new ComparisonContext(po, task);
                                Comparison result = service.compareDocumentParts(context);
                                sendEvent(emitter, emitterActive, ComparisonEvent.Complete.of(result));
                                completeEmitter(emitter, emitterActive);
                            } catch (Exception e) {
                                log.error("Blocking comparison failed", e);
                                sendErrorAndComplete(emitter, emitterActive, "COMPARISON_FAILED", e.getMessage());
                            }
                        },
                        () -> sendErrorAndComplete(emitter, emitterActive, "PO_NOT_FOUND", "Purchase order not found")
                );
    }

    /**
     * Send an event through the SSE emitter.
     */
    private void sendEvent(SseEmitter emitter, AtomicBoolean emitterActive, ComparisonEvent event) {
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

        sendEvent(emitter, emitterActive, eventType, data);
    }

    /**
     * Send a typed event through the SSE emitter.
     */
    private void sendEvent(SseEmitter emitter, AtomicBoolean emitterActive, String eventType, Object data) {
        if (!emitterActive.get()) {
            log.debug("Skipping SSE event (client disconnected): {}", eventType);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(eventType)
                    .data(json, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitterActive.set(false);
            log.debug("Client disconnected during SSE send: {}", e.getMessage());
        }
    }

    /**
     * Send an error event and complete the emitter.
     */
    private void sendErrorAndComplete(SseEmitter emitter, AtomicBoolean emitterActive, String code, String message) {
        if (!emitterActive.get()) {
            log.debug("Skipping error event (client disconnected): {}", code);
            return;
        }
        try {
            String json = String.format("{\"message\":\"%s\",\"code\":\"%s\"}",
                    message.replace("\"", "\\\""), code);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(json, MediaType.APPLICATION_JSON));
            emitter.complete();
            emitterActive.set(false);
        } catch (IOException | IllegalStateException e) {
            emitterActive.set(false);
            log.debug("Client disconnected during error send: {}", e.getMessage());
        }
    }

    /**
     * Complete the emitter safely.
     */
    private void completeEmitter(SseEmitter emitter, AtomicBoolean emitterActive) {
        if (!emitterActive.get()) {
            return;
        }
        try {
            emitter.complete();
            emitterActive.set(false);
        } catch (IllegalStateException e) {
            emitterActive.set(false);
            log.debug("Emitter already completed: {}", e.getMessage());
        }
    }
}
