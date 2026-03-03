package com.tosspaper.precon;

import com.tosspaper.aiengine.client.reducto.ReductoClient;
import com.tosspaper.models.exception.ReductoIntermediateStatusException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.supplyAsync;

/**
 * Executes the full extraction pipeline for a single {@link ExtractionWithDocs}:
 * acquires the per-extraction distributed lock, marks the record as
 * {@code PROCESSING}, scatters its documents to the executor pool (scatter),
 * gathers all results (gather), and saves.
 *
 * <h3>Single responsibility</h3>
 * <p>This class owns the <em>how</em> of processing one extraction.
 * The <em>when</em> (scheduling, poll cadence) is the responsibility of
 * {@link ExtractionPollJob}.
 *
 * <h3>Error handling — prepare vs checkback</h3>
 * <p>Two distinct failure modes are handled differently in
 * {@link #scatterGather(ExtractionWithDocs)}:
 * <ul>
 *   <li><strong>Prepare failure</strong> — a hard error during document
 *       submission (e.g. network timeout, API error). The extraction is
 *       marked {@code FAILED} and the lock is released.</li>
 *   <li><strong>Checkback intermediate status</strong> — Reducto returned
 *       {@code PENDING} or {@code PROCESSING} (task still in-flight).
 *       Signalled by {@link ReductoIntermediateStatusException}. The
 *       extraction is <em>not</em> marked {@code FAILED}; only the lock is
 *       released so the next poll cycle can retry.</li>
 * </ul>
 *
 * <p>TODO [TOS-38] Wire {@link #callReducto} to the real Reducto extraction
 * flow once the document-to-extraction pipeline is finalised.
 */
@Slf4j
@Component
public class ExtractionPipelineRunner {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionLockManager lockManager;
    private final ReductoClient reductoClient;
    private final Executor reductoExecutor;

    public ExtractionPipelineRunner(
            PreconExtractionRepository preconExtractionRepository,
            ExtractionLockManager lockManager,
            ReductoClient reductoClient,
            @Qualifier("extractionProcessingExecutor") Executor reductoExecutor) {
        this.preconExtractionRepository = preconExtractionRepository;
        this.lockManager = lockManager;
        this.reductoClient = reductoClient;
        this.reductoExecutor = reductoExecutor;
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Attempts to run the full extraction pipeline for the given extraction.
     *
     * <p>Acquires the per-extraction Redisson lock (no-wait). If the lock is
     * already held by another instance the extraction is silently skipped.
     * Otherwise: marks the record as {@code PROCESSING} in the current thread
     * (DB guard against re-fetch), then dispatches documents via
     * {@link #scatterGather(ExtractionWithDocs)}.
     *
     * @param extraction the extraction to process
     */
    public void execute(ExtractionWithDocs extraction) {
        String extractionId = extraction.getId();

        boolean locked = lockManager.tryWithExtractionLock(extractionId, () -> {
            preconExtractionRepository.markAsProcessing(extractionId);
            scatterGather(extraction);
        });

        if (!locked) {
            log.debug("[ExtractionPipeline] Skipping extraction {} — lock held by another instance",
                    extractionId);
        }
    }

    // ── Scatter-gather ────────────────────────────────────────────────────────

    /**
     * Submits all documents to the shared executor pool, then wires the
     * resulting {@code allOf()} to call {@link #combineAndSave} on success.
     *
     * <p>On failure, the {@code exceptionally} handler distinguishes between
     * a hard prepare failure (marks {@code FAILED}) and a Reducto intermediate
     * status (releases lock only — retry on next poll cycle).
     *
     * @param extraction the extraction and its document IDs
     */
    void scatterGather(ExtractionWithDocs extraction) {
        String extractionId = extraction.getId();
        List<String> docIds = extraction.documentIds();

        if (docIds.isEmpty()) {
            log.debug("[ExtractionPipeline] No documents to process for extraction {}", extractionId);
            combineAndSave(extractionId, List.of());
            return;
        }

        List<CompletableFuture<DocResult>> futures = docIds.stream()
                .map(docId -> supplyAsync(() -> callReducto(docId), reductoExecutor))
                .collect(Collectors.toList());

        allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> combineAndSave(extractionId, futures))
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                    lockManager.releaseLock(extractionId);
                    if (cause instanceof ReductoIntermediateStatusException ise) {
                        log.debug("[ExtractionPipeline] Checkback for extraction {} returned"
                                        + " intermediate status {} — releasing lock; will retry next cycle",
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
     * Runs on a thread from {@code reductoExecutor} — never on the scheduler
     * thread.
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
     * <p>The per-extraction lock is released in the {@code finally} block so
     * that it is freed even if the save step throws.
     *
     * @param extractionId the extraction to finalise
     * @param futures      the completed per-document futures
     */
    void combineAndSave(String extractionId, List<CompletableFuture<DocResult>> futures) {
        try {
            List<DocResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            log.info("[ExtractionPipeline] Combining {} doc result(s) for extraction {}",
                    results.size(), extractionId);

            // TODO [TOS-38] Persist combined results to extraction_fields once
            //  the real Reducto response is wired.

            preconExtractionRepository.markAsCompleted(extractionId);
            log.info("[ExtractionPipeline] Extraction {} marked as COMPLETED", extractionId);
        } finally {
            lockManager.releaseLock(extractionId);
        }
    }
}
