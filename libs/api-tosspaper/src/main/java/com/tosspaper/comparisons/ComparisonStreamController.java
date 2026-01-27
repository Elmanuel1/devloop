package com.tosspaper.comparisons;

import com.tosspaper.aiengine.agent.ComparisonEvent;
import com.tosspaper.aiengine.properties.ComparisonProperties;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.aiengine.service.impl.StreamingDocumentComparisonService;
import com.tosspaper.common.HeaderUtils;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for streaming document comparison via Server-Sent Events (SSE).
 *
 * <p>Provides real-time progress updates during AI-powered document comparison.
 * Only active when streaming is enabled ({@code ai.comparison.streaming.enabled=true}).
 *
 * <h2>API Flow</h2>
 * <ol>
 *   <li>POST /api/v1/comparisons/{assignedId}/stream - Start comparison, returns taskId</li>
 *   <li>GET /api/v1/comparisons/stream/{taskId} - Connect to SSE stream for events</li>
 * </ol>
 *
 * <h2>Event Types</h2>
 * <ul>
 *   <li>activity - User-friendly progress messages</li>
 *   <li>thinking - AI reasoning (Claude extended thinking)</li>
 *   <li>finding - Individual comparison findings</li>
 *   <li>complete - Final result with summary</li>
 *   <li>error - Error information</li>
 * </ul>
 *
 * <h2>Frontend Example (JavaScript)</h2>
 * <pre>{@code
 * const { taskId } = await fetch('/api/v1/comparisons/abc123/stream', { method: 'POST' })
 *     .then(r => r.json());
 *
 * const source = new EventSource(`/api/v1/comparisons/stream/${taskId}`);
 * source.addEventListener('activity', e => console.log(JSON.parse(e.data)));
 * source.addEventListener('complete', e => { source.close(); });
 * }</pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/comparisons")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.comparison.streaming.enabled", havingValue = "true")
@ConditionalOnBean(StreamingDocumentComparisonService.class)
public class ComparisonStreamController {

    private final StreamingDocumentComparisonService comparisonService;
    private final ExtractionTaskRepository extractionTaskRepository;
    private final PurchaseOrderLookupService poLookupService;
    private final ComparisonProperties properties;

    /**
     * Active comparison streams indexed by task ID.
     * Cleaned up after completion or timeout.
     */
    private final Map<String, Sinks.Many<ComparisonEvent>> activeStreams = new ConcurrentHashMap<>();

    /**
     * Start a streaming comparison for a document.
     *
     * @param xContextId Company context header
     * @param assignedId Extraction task assigned ID
     * @return Task ID for connecting to the SSE stream
     */
    @PostMapping("/{assignedId}/stream")
    @PreAuthorize("hasPermission(#xContextId, 'company', 'documents:edit')")
    public ResponseEntity<Map<String, String>> startStreamingComparison(
            @RequestHeader("X-Context-Id") String xContextId,
            @PathVariable String assignedId) {

        Long companyId = HeaderUtils.parseCompanyId(xContextId);
        log.info("Starting streaming comparison: assignedId={}, companyId={}", assignedId, companyId);

        // Validate extraction task exists and belongs to company
        ExtractionTask task = extractionTaskRepository.findByAssignedId(assignedId);
        if (task == null) {
            log.warn("Extraction task not found: {}", assignedId);
            return ResponseEntity.notFound().build();
        }

        if (!task.getCompanyId().equals(companyId)) {
            log.warn("Company mismatch for task: {} (expected={}, actual={})",
                    assignedId, companyId, task.getCompanyId());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (task.getPurchaseOrderId() == null || task.getPurchaseOrderId().isBlank()) {
            log.warn("No PO linked for task: {}", assignedId);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "NO_PO_LINKED", "message", "No purchase order linked"));
        }

        if (task.getConformedJson() == null || task.getConformedJson().isBlank()) {
            log.warn("Document not conformed: {}", assignedId);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "NOT_CONFORMED", "message", "Document has not been conformed"));
        }

        // Generate task ID for this comparison
        String taskId = UUID.randomUUID().toString();

        // Create sink for events
        Sinks.Many<ComparisonEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        activeStreams.put(taskId, sink);

        // Look up PO and start comparison in background
        poLookupService.getPoWithItemsByPoNumber(companyId, task.getPoNumber())
                .ifPresentOrElse(
                        po -> {
                            ComparisonContext context = new ComparisonContext(po, task);

                            comparisonService.executeComparisonStream(context)
                                    .doOnNext(event -> sink.tryEmitNext(event))
                                    .doOnError(error -> {
                                        log.error("Streaming comparison error: taskId={}", taskId, error);
                                        sink.tryEmitNext(ComparisonEvent.Error.of(error.getMessage()));
                                        sink.tryEmitComplete();
                                        activeStreams.remove(taskId);
                                    })
                                    .doOnComplete(() -> {
                                        log.info("Streaming comparison completed: taskId={}", taskId);
                                        sink.tryEmitComplete();
                                        activeStreams.remove(taskId);
                                    })
                                    .subscribe();
                        },
                        () -> {
                            log.error("PO not found for task: {}", assignedId);
                            sink.tryEmitNext(ComparisonEvent.Error.of("Purchase order not found"));
                            sink.tryEmitComplete();
                            activeStreams.remove(taskId);
                        }
                );

        log.info("Comparison stream started: taskId={}, assignedId={}", taskId, assignedId);

        return ResponseEntity.ok(Map.of("taskId", taskId));
    }

    /**
     * Connect to an SSE stream for a comparison task.
     *
     * @param taskId Task ID from startStreamingComparison
     * @return Server-Sent Events stream
     */
    @GetMapping(value = "/stream/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ComparisonEvent>> streamComparison(@PathVariable String taskId) {
        log.debug("SSE connection requested: taskId={}", taskId);

        Sinks.Many<ComparisonEvent> sink = activeStreams.get(taskId);
        if (sink == null) {
            log.warn("Stream not found: taskId={}", taskId);
            return Flux.just(ServerSentEvent.<ComparisonEvent>builder()
                    .event("error")
                    .data(ComparisonEvent.Error.of("Stream not found or expired", "STREAM_NOT_FOUND"))
                    .build());
        }

        return sink.asFlux()
                .map(event -> {
                    String eventType = getEventType(event);
                    return ServerSentEvent.<ComparisonEvent>builder()
                            .event(eventType)
                            .data(event)
                            .build();
                })
                .doOnSubscribe(sub -> log.debug("SSE client subscribed: taskId={}", taskId))
                .doOnCancel(() -> {
                    log.debug("SSE client disconnected: taskId={}", taskId);
                    activeStreams.remove(taskId);
                });
    }

    /**
     * Get the SSE event type name for a ComparisonEvent.
     */
    private String getEventType(ComparisonEvent event) {
        return switch (event) {
            case ComparisonEvent.Activity a -> "activity";
            case ComparisonEvent.Thinking t -> "thinking";
            case ComparisonEvent.Finding f -> "finding";
            case ComparisonEvent.Complete c -> "complete";
            case ComparisonEvent.Error e -> "error";
        };
    }

    /**
     * Check if a stream is still active.
     *
     * @param taskId Task ID
     * @return Status of the stream
     */
    @GetMapping("/stream/{taskId}/status")
    public ResponseEntity<Map<String, Object>> getStreamStatus(@PathVariable String taskId) {
        boolean active = activeStreams.containsKey(taskId);
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "active", active,
                "activeStreams", activeStreams.size()
        ));
    }
}
