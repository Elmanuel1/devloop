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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPipelineRunner {

    private final PreconExtractionRepository preconExtractionRepository;

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
                .map(extraction -> CompletableFuture
                        .supplyAsync(() -> callReducto(extraction), extractionProcessingExecutor)
                        .thenAccept(result -> preconExtractionRepository.markAsCompleted(extraction.getId(), result))
                        .exceptionally(ex -> {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            log.error("[ExtractionPipeline] Failed for extraction {}: {}",
                                    extraction.getId(), cause.getMessage(), cause);
                            preconExtractionRepository.markAsFailed(extraction.getId(), cause.getMessage());
                            return null;
                        }))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    // ── Per-extraction call ───────────────────────────────────────────────────

    /**
     * Calls the Reducto client for the given extraction.
     * Runs on a virtual thread from the bounded {@code extractionProcessingExecutor}.
     *
     * <p>TODO [TOS-38] Wire to the real Reducto extraction flow once the
     * document-to-extraction pipeline is finalised.
     *
     * @param extraction the full extraction context (entity type, entity ID, etc.)
     * @return the extraction result
     * @throws UnsupportedOperationException always, until TOS-38 is implemented
     */
    PipelineExtractionResult callReducto(ExtractionWithDocs extraction) {
        log.debug("[ExtractionPipeline] callReducto invoked for extraction {} — not yet implemented (TOS-38)",
                extraction.getId());
        throw new UnsupportedOperationException(
                "callReducto is not implemented yet (TOS-38). Extraction: %s".formatted(extraction.getId()));
    }
}
