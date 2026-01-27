package com.tosspaper.aiengine.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.agent.ComparisonEvent;
import com.tosspaper.aiengine.agent.StreamingComparisonAgent;
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.service.PurchaseOrderLookupService;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * Streaming implementation of DocumentPartComparisonService.
 * Uses ChatClient with embedded Java tools instead of CLI-based agent.
 *
 * <p>Activated when {@code ai.comparison.streaming.enabled=true}.
 *
 * <p>Benefits over CLI-based implementation:
 * <ul>
 *   <li>~1MB per request vs ~500MB for Node.js CLI process</li>
 *   <li>100+ concurrent requests per 4GB instance (vs 2-3)</li>
 *   <li>Real-time SSE streaming of progress</li>
 *   <li>Embedded file tools (no process spawn overhead)</li>
 *   <li>Multi-provider support (OpenAI, Anthropic)</li>
 * </ul>
 *
 * <p>Provides both:
 * <ul>
 *   <li>{@link #executeComparisonStream(ComparisonContext)} - Streaming for SSE</li>
 *   <li>{@link #compareDocumentParts(ComparisonContext)} - Blocking for backwards compatibility</li>
 * </ul>
 */
@Slf4j
@Service
@Primary
@ConditionalOnProperty(name = "ai.comparison.streaming.enabled", havingValue = "true")
public class StreamingDocumentComparisonService extends AbstractDocumentPartComparisonService {

    private final StreamingComparisonAgent streamingAgent;

    public StreamingDocumentComparisonService(
            ObjectMapper objectMapper,
            DocumentPartComparisonRepository repository,
            ExtractionTaskRepository extractionTaskRepository,
            DSLContext dslContext,
            StreamingComparisonAgent streamingAgent,
            PurchaseOrderLookupService poService) {
        super(objectMapper, repository, extractionTaskRepository, dslContext, poService);
        this.streamingAgent = streamingAgent;
        log.info("StreamingDocumentComparisonService initialized - using ChatClient with embedded tools");
    }

    /**
     * Execute document comparison with streaming events.
     * Use this for SSE streaming to the frontend.
     *
     * @param context Comparison context containing PO and extraction task
     * @return Flux of ComparisonEvents for real-time progress updates
     */
    public Flux<ComparisonEvent> executeComparisonStream(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();

        log.info("Starting streaming comparison: companyId={}, extractionId={}, poNumber={}",
                task.getCompanyId(), task.getAssignedId(), task.getPoNumber());

        return streamingAgent.executeComparison(context)
                .doOnComplete(() -> log.info("Streaming comparison completed: extractionId={}",
                        task.getAssignedId()))
                .doOnError(error -> log.error("Streaming comparison failed: extractionId={}",
                        task.getAssignedId(), error));
    }

    /**
     * Blocking implementation for backwards compatibility.
     * Waits for streaming to complete and returns the final result.
     *
     * @param context Comparison context containing PO and extraction task
     * @return Comparison result
     */
    @Override
    @Observed(
            name = "document.part.comparison.streaming",
            contextualName = "Compare Document Parts (Streaming)",
            lowCardinalityKeyValues = {"service", "comparison", "operation", "compare-parts-streaming"}
    )
    public Comparison compareDocumentParts(ComparisonContext context) {
        ExtractionTask task = context.extractionTask();

        log.info("Starting blocking comparison (streaming internally): companyId={}, extractionId={}",
                task.getCompanyId(), task.getAssignedId());

        // Execute streaming and wait for Complete event
        return executeComparisonStream(context)
                .filter(event -> event instanceof ComparisonEvent.Complete)
                .map(event -> ((ComparisonEvent.Complete) event).result())
                .blockFirst();
    }
}
