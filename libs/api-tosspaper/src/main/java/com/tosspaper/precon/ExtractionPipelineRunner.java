package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Processes a batch of claimed extractions by submitting each one to the
 * bounded virtual-thread executor and gathering results.
 *
 * <p>This class owns concurrency (fan-out via {@code CompletableFuture}, allOf join)
 * while {@link ExtractionWorker} owns per-extraction business logic
 * (classify → submit → validate).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPipelineRunner {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionWorker extractionWorker;

    @Qualifier("extractionProcessingExecutor")
    private final Executor extractionProcessingExecutor;

    /**
     * Runs the extraction pipeline for an entire claimed batch.
     * All extractions are submitted in parallel; a failure in one does not
     * affect others.
     *
     * @param batch the list of claimed extractions to process
     */
    public void run(List<ExtractionWithDocs> batch) {
        if (batch.isEmpty()) {
            return;
        }
        log.debug("[ExtractionPipeline] Starting pipeline for batch of {} extraction(s)", batch.size());

        List<CompletableFuture<Void>> futures = batch.stream()
                .map(this::processExtraction)
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    // ── Per-extraction pipeline chain ─────────────────────────────────────────

    /**
     * Builds the async pipeline chain for a single extraction: delegates to
     * {@link ExtractionWorker#process}, marks completed on success, marks
     * failed on error.
     */
    private CompletableFuture<Void> processExtraction(ExtractionWithDocs extraction) {
        return CompletableFuture
                .supplyAsync(() -> callReducto(extraction), extractionProcessingExecutor)
                .thenAccept(result -> preconExtractionRepository.markAsCompleted(extraction.getId(), result))
                .exceptionally(ex -> handleProcessingFailure(extraction, ex));
    }

    private Void handleProcessingFailure(ExtractionWithDocs extraction, Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        log.error("[ExtractionPipeline] Failed for extraction {}: {}",
                extraction.getId(), cause.getMessage(), cause);
        preconExtractionRepository.markAsFailed(extraction.getId(), cause.getMessage());
        return null;
    }

    // ── Per-extraction call ───────────────────────────────────────────────────

    /**
     * Calls the ExtractionWorker for the given extraction.
     * Runs on a virtual thread from the bounded {@code extractionProcessingExecutor}.
     *
     * @param extraction the full extraction context (entity type, entity ID, document list)
     * @return the pipeline result produced by the worker
     */
    PipelineExtractionResult callReducto(ExtractionWithDocs extraction) {
        log.debug("[ExtractionPipeline] Processing extraction '{}' via ExtractionWorker",
                extraction.getId());
        return extractionWorker.process(extraction);
    }
}
