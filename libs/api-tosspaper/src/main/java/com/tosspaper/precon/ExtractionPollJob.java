package com.tosspaper.precon;

import com.tosspaper.aiengine.client.reducto.ReductoClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * Lifecycle-managed job that polls for {@code PENDING} extractions and
 * dispatches their documents to the fixed processing thread pool.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li><strong>No global job lock.</strong> Every JVM instance polls at the
 *       same cadence. Duplicate processing is prevented by the per-extraction
 *       Redisson lock acquired before any work starts.</li>
 *   <li><strong>Per-extraction lock.</strong> For each pending extraction the
 *       scheduler tries to acquire {@code extraction:lock:{id}} (20-minute
 *       lease, no-wait). Instances that lose the race skip the extraction.</li>
 *   <li><strong>markAsProcessing as secondary DB guard.</strong> Called
 *       immediately after acquiring the per-ID lock so that a slower poll
 *       cycle on the same node does not re-fetch the row.</li>
 *   <li><strong>Flat scatter-gather.</strong> All documents from all
 *       extractions are submitted flat into the shared
 *       {@code reductoExecutor} pool. Futures are then grouped by extraction
 *       ID and each group's {@code allOf()} gathers independently.</li>
 *   <li><strong>ScheduledExecutorService.</strong> A single-threaded
 *       {@link ScheduledExecutorService} drives the poll cycle using
 *       {@code scheduleWithFixedDelay} so consecutive runs never overlap.</li>
 *   <li><strong>SmartLifecycle.</strong> Keeps lifecycle management explicit
 *       and testable — no {@code @PostConstruct} or Spring {@code @Scheduled}.</li>
 * </ul>
 *
 * <p>TODO [TOS-38] Wire {@link #callReducto} to the real Reducto extraction flow
 * once the document-to-extraction pipeline is finalised.
 */
@Slf4j
@Component
public class ExtractionPollJob implements SmartLifecycle {

    /** Maximum number of PENDING rows fetched in a single poll cycle. */
    static final int POLL_BATCH_SIZE = 50;

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionLockManager lockManager;
    private final ReductoClient reductoClient;
    private final Executor reductoExecutor;
    private final long delayMs;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "extraction-poll"));

    private volatile boolean running = false;

    public ExtractionPollJob(
            PreconExtractionRepository preconExtractionRepository,
            ExtractionLockManager lockManager,
            ReductoClient reductoClient,
            @Qualifier("extractionProcessingExecutor") Executor reductoExecutor,
            @Value("${extraction.poll.delay-ms:5000}") long delayMs) {
        this.preconExtractionRepository = preconExtractionRepository;
        this.lockManager = lockManager;
        this.reductoClient = reductoClient;
        this.reductoExecutor = reductoExecutor;
        this.delayMs = delayMs;
    }

    // ── SmartLifecycle ────────────────────────────────────────────────────────

    @Override
    public void start() {
        running = true;
        scheduler.scheduleWithFixedDelay(this::poll, 0, delayMs, TimeUnit.MILLISECONDS);
        log.info("[ExtractionPoll] Scheduler started (fixed delay {} ms)", delayMs);
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("[ExtractionPoll] Scheduler did not terminate within 10 s; forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("[ExtractionPoll] Scheduler stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    // ── Poll logic ────────────────────────────────────────────────────────────

    /**
     * Polls for pending extractions and dispatches each one through the
     * scatter-gather pipeline.
     *
     * <p>No global lock — every instance polls. Per-extraction locks prevent
     * duplicate work.
     */
    void poll() {
        List<ExtractionWithDocs> pending =
                preconExtractionRepository.findPendingExtractions(POLL_BATCH_SIZE);

        if (pending.isEmpty()) {
            log.debug("[ExtractionPoll] No pending extractions found");
            return;
        }

        log.info("[ExtractionPoll] Found {} pending extraction(s) to process", pending.size());

        for (ExtractionWithDocs extraction : pending) {
            String extractionId = extraction.getId();

            boolean locked = lockManager.tryWithExtractionLock(extractionId, () -> {
                preconExtractionRepository.markAsProcessing(extractionId);
                scatterGather(extraction);
            });

            if (!locked) {
                log.debug("[ExtractionPoll] Skipping extraction {} — lock held by another instance",
                        extractionId);
            }
        }
    }

    // ── Scatter-gather ────────────────────────────────────────────────────────

    /**
     * Submits all documents flat into the shared executor pool, groups the
     * resulting futures by extraction ID, then wires each group's
     * {@code allOf()} to call {@link #combineAndSave} on completion.
     *
     * @param pending the extraction and its document IDs
     */
    void scatterGather(ExtractionWithDocs pending) {
        String extractionId = pending.getId();

        Map<String, List<CompletableFuture<DocResult>>> byExtraction =
                pending.documentIds().stream()
                        .map(docId -> Map.entry(
                                extractionId,
                                supplyAsync(() -> callReducto(docId), reductoExecutor)))
                        .collect(groupingBy(
                                Map.Entry::getKey,
                                mapping(Map.Entry::getValue, toList())));

        byExtraction.forEach((id, futures) ->
                allOf(futures.toArray(new CompletableFuture[0]))
                        .thenRun(() -> combineAndSave(id, futures))
                        .exceptionally(ex -> {
                            log.error("[ExtractionPoll] Scatter-gather failed for extraction {}: {}",
                                    id, ex.getMessage(), ex);
                            lockManager.releaseLock(id);
                            preconExtractionRepository.markAsFailed(id);
                            return null;
                        }));
    }

    // ── Per-document call ─────────────────────────────────────────────────────

    /**
     * Calls the Reducto client for a single document.
     * Runs on a thread from {@code reductoExecutor}.
     *
     * <p>TODO [TOS-38] The actual Reducto flow (upload → createAsyncExtractTask)
     * is deferred. This method currently throws {@link UnsupportedOperationException}
     * as a placeholder so the scatter-gather wiring can be tested without
     * requiring real network calls.
     *
     * @param documentId the document to extract
     * @return the extraction result
     * @throws UnsupportedOperationException always, until TOS-38 is implemented
     */
    DocResult callReducto(String documentId) {
        log.debug("[ExtractionPoll] callReducto invoked for document {} — not yet implemented (TOS-38)",
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
     * @param futures      the completed document futures
     */
    void combineAndSave(String extractionId, List<CompletableFuture<DocResult>> futures) {
        try {
            List<DocResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList());

            log.info("[ExtractionPoll] Combining {} doc result(s) for extraction {}",
                    results.size(), extractionId);

            // TODO [TOS-38] Persist combined results to extraction_fields once
            //  the real Reducto response is wired.

            preconExtractionRepository.markAsCompleted(extractionId);
            log.info("[ExtractionPoll] Extraction {} marked as COMPLETED", extractionId);
        } finally {
            lockManager.releaseLock(extractionId);
        }
    }
}
