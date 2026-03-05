package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Scheduled job that polls for {@code PENDING} extractions and hands each one
 * to {@link ExtractionPipelineRunner} for processing.
 *
 * <h3>Single responsibility</h3>
 * <p>This class owns exactly one concern: <em>when</em> to poll.
 * The <em>how</em> (claim the row, scatter-gather documents) lives in
 * {@link ExtractionPipelineRunner}.
 *
 * <h3>Concurrency</h3>
 * <p>{@link #poll()} calls {@link PreconExtractionRepository#claimNextBatch(int)}
 * which atomically transitions {@code PENDING} rows to {@code PROCESSING} via
 * {@code FOR UPDATE SKIP LOCKED} in a single SQL statement. Concurrent JVM
 * instances therefore never claim the same row — no application-level
 * distributed lock is needed.
 *
 * <h3>Stale row recovery</h3>
 * <p>{@link #reap()} runs on a longer cadence and resets any row that has been
 * stuck in {@code PROCESSING} beyond the stale threshold (e.g. the processing
 * node crashed mid-flight). Those rows are returned to {@code PENDING} so the
 * next poll cycle can claim and retry them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPollJob {

    /** Maximum number of PENDING rows claimed in a single poll cycle. */
    static final int POLL_BATCH_SIZE = 50;

    /**
     * Age threshold for the reaper: extractions stuck in {@code PROCESSING}
     * for longer than this are considered abandoned.
     */
    static final int STALE_MINUTES = 10;

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineRunner pipelineRunner;

    // ── Poll ──────────────────────────────────────────────────────────────────

    /**
     * Claims up to {@link #POLL_BATCH_SIZE} pending extractions and fans them
     * out to the pipeline runner in parallel.
     *
     * <p>Uses {@code fixedDelay} so the next cycle starts only after all
     * claimed rows have been dispatched (fire-and-forget via
     * {@link CompletableFuture}). The actual document processing happens on
     * the virtual-thread ForkJoinPool — this thread is free immediately after
     * {@link ExtractionPipelineRunner#run(ExtractionWithDocs)} returns.
     */
    @Scheduled(fixedDelay = 500)
    void poll() {
        List<ExtractionWithDocs> claimed =
                preconExtractionRepository.claimNextBatch(POLL_BATCH_SIZE);

        if (claimed.isEmpty()) {
            log.debug("[ExtractionPoll] No pending extractions found");
            return;
        }

        log.info("[ExtractionPoll] Claimed {} extraction(s) for processing", claimed.size());

        for (ExtractionWithDocs extraction : claimed) {
            pipelineRunner.run(extraction);
        }
    }

    // ── Reaper ────────────────────────────────────────────────────────────────

    /**
     * Resets stale {@code PROCESSING} rows back to {@code PENDING}.
     *
     * <p>Runs every 60 seconds. Any extraction that has been stuck in
     * {@code PROCESSING} for more than {@link #STALE_MINUTES} minutes is
     * assumed abandoned and returned to the queue.
     */
    @Scheduled(fixedRate = 60_000)
    void reap() {
        int reset = preconExtractionRepository.reapStaleExtractions(STALE_MINUTES);
        if (reset > 0) {
            log.warn("[ExtractionPoll] Reaped {} stale PROCESSING extraction(s) back to PENDING", reset);
        } else {
            log.debug("[ExtractionPoll] Reaper found no stale extractions");
        }
    }
}
