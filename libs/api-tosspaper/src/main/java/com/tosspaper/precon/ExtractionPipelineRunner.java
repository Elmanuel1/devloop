package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Fans out a claimed batch of extractions across the virtual-thread executor. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPipelineRunner {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionWorker extractionWorker;

    @Qualifier("extractionProcessingExecutor")
    private final Executor extractionProcessingExecutor;

    /** Runs the pipeline for a claimed batch. */
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

    private CompletableFuture<Void> processExtraction(ExtractionWithDocs extraction) {
        return CompletableFuture
                .supplyAsync(() -> extractionWorker.process(extraction), extractionProcessingExecutor)
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
}
