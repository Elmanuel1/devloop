package com.tosspaper.comparisons;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.agent.ComparisonEvent;
import com.tosspaper.aiengine.properties.ComparisonProperties;
import com.tosspaper.aiengine.service.impl.StreamingDocumentComparisonService;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.generated.model.DocumentComparisonResult;
import com.tosspaper.models.exception.BadRequestException;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for document part comparison operations.
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
    private final PurchaseOrderLookupService poLookupService;
    private final ObjectMapper objectMapper;
    private final ComparisonProperties comparisonProperties;

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

        // Configurable timeout for long AI comparisons (default 5 minutes)
        long timeoutMs = comparisonProperties.getStreaming().getTimeoutSeconds() * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);
        AtomicBoolean emitterActive = new AtomicBoolean(true);

        Long companyId = HeaderUtils.parseCompanyId(xContextId);

        // Handle client disconnect
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

        // Generate unique comparisonId for session isolation
        String comparisonId = assignedId + "-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Generated comparisonId: {}", comparisonId);

        // Execute comparison in background thread
        executor.execute(() -> {
            try {
                sendEvent(emitter, emitterActive, ComparisonEvent.Activity.processing());

                if (service instanceof StreamingDocumentComparisonService streamingService) {
                    // Streaming: use triggerComparisonStream which validates, runs comparison, and saves
                    streamingService.triggerComparisonStream(assignedId, companyId, comparisonId)
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
                } else {
                    // Blocking fallback
                    executeBlockingComparison(emitter, emitterActive, assignedId, companyId);
                }
            } catch (BadRequestException e) {
                log.warn("Comparison validation failed: {}", e.getMessage());
                sendErrorAndComplete(emitter, emitterActive, e.getCode(), e.getMessage());
            } catch (Exception e) {
                log.error("Comparison failed: assignedId={}", assignedId, e);
                sendErrorAndComplete(emitter, emitterActive, "COMPARISON_FAILED", e.getMessage());
            }
        });

        return emitter;
    }

    /**
     * Execute blocking comparison (fallback when streaming not available).
     */
    private void executeBlockingComparison(SseEmitter emitter, AtomicBoolean emitterActive,
                                           String assignedId, Long companyId) {
        service.manuallyTriggerComparison(assignedId, companyId);

        // Fetch and send the result
        service.getComparisonByAssignedId(assignedId, companyId)
                .ifPresentOrElse(
                        result -> {
                            // Blocking fallback doesn't generate comparisonId (result from DB)
                            sendEvent(emitter, emitterActive, ComparisonEvent.Complete.of(result, null));
                            completeEmitter(emitter, emitterActive);
                        },
                        () -> sendErrorAndComplete(emitter, emitterActive, "COMPARISON_FAILED", "Comparison completed but result not found")
                );
    }

    /**
     * Send an event through the SSE emitter.
     */
    private void sendEvent(SseEmitter emitter, AtomicBoolean emitterActive, ComparisonEvent event) {
        if (!emitterActive.get()) {
            log.debug("Skipping SSE event (client disconnected): {}", event.eventType());
            return;
        }

        log.debug("Sending SSE event: {}", event.eventType());

        try {
            Object data = event instanceof ComparisonEvent.Complete c
                    ? mapper.toDto(c.result())
                    : event;

            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .data(json, MediaType.APPLICATION_JSON));

            if (event instanceof ComparisonEvent.Complete) {
                log.info("Sent complete event to client");
            }
        } catch (Exception e) {
            emitterActive.set(false);
            log.error("Failed to send SSE event {}: {}", event.eventType(), e.getMessage(), e);
        }
    }

    /**
     * Send an error event and complete the emitter.
     */
    private void sendErrorAndComplete(SseEmitter emitter, AtomicBoolean emitterActive, String code, String message) {
        if (!emitterActive.get()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(
                    java.util.Map.of("message", message, "code", code));
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(json, MediaType.APPLICATION_JSON));
            emitter.complete();
            emitterActive.set(false);
        } catch (IOException | IllegalStateException e) {
            emitterActive.set(false);
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
        }
    }
}
