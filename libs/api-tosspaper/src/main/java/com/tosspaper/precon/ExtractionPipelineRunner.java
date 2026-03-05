package com.tosspaper.precon;

import com.tosspaper.models.exception.ReductoIntermediateStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.supplyAsync;

/**
 * Executes the full extraction pipeline for a batch of {@link ExtractionWithDocs}
 * records: scatters all documents across the batch to the bounded virtual-thread
 * executor in one shot, gathers results per extraction, and saves.
 *
 * <h3>Single responsibility</h3>
 * <p>This class owns the <em>how</em> of processing a batch of extractions.
 * The <em>when</em> (scheduling, poll cadence, reaping) is the responsibility
 * of {@link ExtractionPollJob}.
 *
 * <h3>No distributed lock</h3>
 * <p>Row ownership is established by {@link PreconExtractionRepository#claimNextBatch(int)}
 * before this runner is called. By the time {@link #run(List)} is invoked every
 * row in the batch is already in {@code PROCESSING} status, so no application-level
 * lock is required here.
 *
 * <h3>Parallelism</h3>
 * <p>{@link #run(List)} submits all per-document tasks across the entire batch in
 * one shot via {@link CompletableFuture#supplyAsync} to the bounded virtual-thread
 * executor ({@code extractionProcessingExecutor}).  The pool size defaults to 5
 * and is configurable via {@code extraction.processing.thread-pool-size}.
 * Virtual threads mean blocking I/O (Reducto HTTP calls) does not consume OS
 * carrier threads, while the bound caps simultaneous in-flight requests to
 * the external service.
 *
 * <h3>Error handling — prepare vs checkback</h3>
 * <p>Two distinct failure modes are handled differently inside
 * {@link #scatterGather(ExtractionWithDocs)}:
 * <ul>
 *   <li><strong>Prepare failure</strong> — a hard error during document
 *       submission (e.g. network timeout, API error). The extraction is
 *       marked {@code FAILED} permanently.</li>
 *   <li><strong>Checkback intermediate status</strong> — Reducto returned
 *       {@code PENDING} or {@code PROCESSING} (task still in-flight).
 *       Signalled by {@link ReductoIntermediateStatusException}. The
 *       extraction is <em>not</em> marked {@code FAILED}; the reaper will
 *       eventually reset the row so the next poll cycle can retry.</li>
 * </ul>
 *
 * <p>TODO [TOS-38] Wire {@link #callReducto} to the real Reducto extraction
 * flow once the document-to-extraction pipeline is finalised.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPipelineRunner {

    private final PreconExtractionRepository preconExtractionRepository;

    @Qualifier("extractionProcessingExecutor")
    private final Executor extractionProcessingExecutor;

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Runs the full extraction pipeline for an entire claimed batch.
     *
     * <p>All documents across all extractions in the batch are submitted as
     * parallel futures in one shot — there is no sequential loop over the list.
     * Each extraction's futures are grouped and gathered independently so that a
     * failure in one extraction does not affect others in the same batch.
     *
     * <p>All rows are already in {@code PROCESSING} status when this method is
     * called (claimed by {@link PreconExtractionRepository#claimNextBatch(int)}).
     *
     * @param batch the list of claimed extractions to process
     */
    public void run(List<ExtractionWithDocs> batch) {
        if (batch.isEmpty()) {
            return;
        }
        log.debug("[ExtractionPipeline] Starting pipeline for batch of {} extraction(s)", batch.size());

        // Fan out: submit all per-extraction scatter-gather chains in one shot.
        // Each CompletableFuture returned by scatterGather already carries its
        // own exceptionally handler, so failures are isolated per extraction.
        List<CompletableFuture<Void>> batchFutures = batch.stream()
                .map(this::scatterGather)
                .toList();

        // Join all chains so the poll cycle waits for the whole batch before
        // the next fixedDelay fires. Processing work itself runs on virtual threads.
        allOf(batchFutures.toArray(new CompletableFuture[0])).join();
    }

    // ── Per-extraction scatter-gather ─────────────────────────────────────────

    /**
     * Submits all documents for a single extraction to the bounded virtual-thread
     * executor via {@code supplyAsync}, then wires the resulting {@code allOf()}
     * to call {@link #combineAndSave} on success.
     *
     * <p>Returns a {@link CompletableFuture}{@code <Void>} so that {@link #run(List)}
     * can gather all extractions in the batch with a single {@code allOf}.
     *
     * <p>On failure, the {@code exceptionally} handler distinguishes between
     * a hard prepare failure (marks {@code FAILED}) and a Reducto intermediate
     * status (no-op — the reaper will reset the row on the next reap cycle).
     *
     * @param extraction the extraction and its document IDs
     * @return a future that completes when this extraction's pipeline has finished
     *         (either successfully or via its own error handler)
     */
    CompletableFuture<Void> scatterGather(ExtractionWithDocs extraction) {
        String extractionId = extraction.getId();
        List<String> docIds = extraction.documentIds();

        if (docIds.isEmpty()) {
            log.debug("[ExtractionPipeline] No documents to process for extraction {}", extractionId);
            combineAndSave(extractionId, List.of());
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<DocResult>> futures = docIds.stream()
                .map(docId -> supplyAsync(() -> callReducto(docId), extractionProcessingExecutor))
                .toList();

        return allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> combineAndSave(extractionId, futures))
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                    if (cause instanceof ReductoIntermediateStatusException ise) {
                        log.debug("[ExtractionPipeline] Checkback for extraction {} returned"
                                        + " intermediate status {} — reaper will reset row on next cycle",
                                extractionId, ise.getStatus());
                    } else {
                        log.error("[ExtractionPipeline] Scatter-gather failed for extraction {}: {}",
                                extractionId, cause.getMessage(), cause);
                        preconExtractionRepository.markAsFailed(extractionId);
                    }
                    return null;
                });
    }

    // ── Per-document call ─────────────────────────────────────────────────────

    /**
     * Calls the Reducto client for a single document.
     * Runs on a virtual thread from the bounded {@code extractionProcessingExecutor}
     * — never on the scheduler thread.
     *
     * <p>TODO [TOS-38] The actual Reducto flow (upload → createAsyncExtractTask
     * → checkback) is deferred. Until TOS-38 is implemented this method throws
     * {@link UnsupportedOperationException} as a placeholder so the
     * scatter-gather wiring can be tested independently.
     *
     * <p>When Reducto responds with an intermediate status ({@code PENDING} or
     * {@code PROCESSING}) this method must throw
     * {@link ReductoIntermediateStatusException} rather than a generic
     * exception, so that the caller's {@code exceptionally} handler can
     * distinguish a transient wait from a real failure.
     *
     * @param documentId the document to extract
     * @return the extraction result
     * @throws UnsupportedOperationException always, until TOS-38 is implemented
     */
    DocResult callReducto(String documentId) {
        log.debug("[ExtractionPipeline] callReducto invoked for document {} — not yet implemented (TOS-38)",
                documentId);
        throw new UnsupportedOperationException(
                "callReducto is not implemented yet (TOS-38). Document: %s".formatted(documentId));
    }

    // ── Combine and save ──────────────────────────────────────────────────────

    /**
     * Collects all per-document results, merges them, saves, and marks the
     * extraction as {@code COMPLETED}.
     *
     * @param extractionId the extraction to finalise
     * @param futures      the completed per-document futures
     */
    void combineAndSave(String extractionId, List<CompletableFuture<DocResult>> futures) {
        List<DocResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        log.info("[ExtractionPipeline] Combining {} doc result(s) for extraction {}",
                results.size(), extractionId);

        // TODO [TOS-38] Persist combined results to extraction_fields once
        //  the real Reducto response is wired.

        preconExtractionRepository.markAsCompleted(extractionId);
        log.info("[ExtractionPipeline] Extraction {} marked as COMPLETED", extractionId);
    }
}
