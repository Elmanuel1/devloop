package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduled job that polls for {@code PENDING} extractions and hands each batch
 * to {@link ExtractionPipelineRunner} for processing.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>{@link #poll()} — claims up to
 *       {@link ExtractionProcessingProperties#getBatchSize()} {@code PENDING} rows
 *       using the SKIP LOCKED claim pattern and submits the batch to the pipeline
 *       runner. Runs at startup (initialDelay 0) and on a fixed delay thereafter.</li>
 *   <li>{@link #reap()} — resets {@code PROCESSING} rows older than
 *       {@link ExtractionProcessingProperties#getStaleMinutes()} minutes back to
 *       {@code PENDING} so the next poll cycle can retry them.
 *       Runs every 60 seconds.</li>
 * </ul>
 *
 * <p>Both thresholds are configured via {@link ExtractionProcessingProperties}
 * ({@code extraction.processing.batch-size}, {@code extraction.processing.stale-minutes}).
 * This class has no dependency on any extraction provider (Reducto or otherwise) —
 * it only knows about claiming work and handing it to the pipeline runner.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPollJob {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineRunner pipelineRunner;
    private final ExtractionProcessingProperties processingProperties;

    // ── Poll ──────────────────────────────────────────────────────────────────

    /**
     * Claims up to {@link ExtractionProcessingProperties#getBatchSize()} pending
     * extractions and submits the entire batch to the pipeline runner in one call.
     *
     * <p>The SKIP LOCKED claim is atomic — concurrent scheduler threads never
     * process the same row twice.
     */
    @Scheduled(initialDelay = 0, fixedDelay = 500)
    void poll() {
        int batchSize = processingProperties.getBatchSize();
        List<ExtractionWithDocs> claimed =
                preconExtractionRepository.claimNextBatch(batchSize);

        if (claimed.isEmpty()) {
            log.debug("[ExtractionPoll] No pending extractions found");
            return;
        }

        log.info("[ExtractionPoll] Claimed {} extraction(s) — status set to PROCESSING, submitting now",
                claimed.size());
        pipelineRunner.run(claimed);
    }

    // ── Reaper ────────────────────────────────────────────────────────────────

    /**
     * Resets stale {@code PROCESSING} rows back to {@code PENDING}.
     * Delegates to {@link PreconExtractionRepository#reapStaleExtractions(int)}.
     * Runs every 60 seconds.
     */
    @Scheduled(fixedRate = 60_000)
    void reap() {
        int staleMinutes = processingProperties.getStaleMinutes();
        int reset = preconExtractionRepository.reapStaleExtractions(staleMinutes);
        if (reset > 0) {
            log.warn("[ExtractionPoll] Reaped {} stale PROCESSING extraction(s) back to PENDING", reset);
        } else {
            log.debug("[ExtractionPoll] Reaper found no stale extractions");
        }
    }
}
