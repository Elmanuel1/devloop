package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled job that polls for {@code PENDING} extractions and hands each batch
 * to {@link ExtractionPipelineRunner} for processing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPollJob {

    /** Maximum number of PENDING rows claimed in a single poll cycle. */
    static final int POLL_BATCH_SIZE = 50;

    /** Age threshold for the reaper: extractions stuck longer than this are reset. */
    static final int STALE_MINUTES = 10;

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineRunner pipelineRunner;

    // ── Poll ──────────────────────────────────────────────────────────────────

    /**
     * Claims up to {@link #POLL_BATCH_SIZE} pending extractions and submits the
     * entire batch to the pipeline runner in one call.
     */
    @Scheduled(fixedDelay = 500)
    void poll() {
        List<ExtractionWithDocs> claimed =
                preconExtractionRepository.claimNextBatch(POLL_BATCH_SIZE);

        if (claimed.isEmpty()) {
            log.debug("[ExtractionPoll] No pending extractions found");
            return;
        }

        log.info("[ExtractionPoll] Claimed {} extraction(s) — status set to PROCESSING, submitting now", claimed.size());
        pipelineRunner.run(claimed);
    }

    // ── Reaper ────────────────────────────────────────────────────────────────

    /**
     * Resets stale {@code PROCESSING} rows back to {@code PENDING}.
     * Runs every 60 seconds.
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
