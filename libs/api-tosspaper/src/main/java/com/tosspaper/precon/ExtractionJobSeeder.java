package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup seeder that queries for {@code PENDING} extractions and enqueues them
 * for processing via {@link ExtractionPipelineRunner}.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>{@link #seedPendingExtractions()} — queries up to
 *       {@link ReductoProperties#getBatchSize()} {@code PENDING} rows using the
 *       SKIP LOCKED claim pattern and hands the batch to the pipeline runner.
 *       Runs at startup (initialDelay 0) and on a fixed delay thereafter.</li>
 *   <li>{@link #reapStuckJobs()} — resets {@code PROCESSING} rows older than
 *       {@link ReductoProperties#getStaleMinutes()} minutes back to {@code PENDING}
 *       so the next seeder cycle can retry them.
 *       Delegates to {@link PreconExtractionRepository#reapStaleExtractions(int)},
 *       which already exists from TOS-37.</li>
 * </ul>
 *
 * <h3>Hard cap</h3>
 * <p>The batch size is configured via {@link ReductoProperties#getBatchSize()} and
 * defaults to {@code 20}, aligned with the 20-document-per-extraction limit enforced
 * at creation time by {@link TenderExtractionAdapter}.
 *
 * <h3>Relation to ExtractionPollJob</h3>
 * <p>This class supersedes {@link ExtractionPollJob} by replacing magic constants
 * with {@code @ConfigurationProperties} and by renaming the reaper method to
 * {@code reapStuckJobs()} to match the Confluence spec. {@code ExtractionPollJob}
 * is retained for backward compatibility during the transition period.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionJobSeeder {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineRunner pipelineRunner;
    private final ReductoProperties reductoProperties;

    // ── Seeder ────────────────────────────────────────────────────────────────

    /**
     * Queries for up to {@link ReductoProperties#getBatchSize()} {@code PENDING}
     * extractions using {@code FOR UPDATE SKIP LOCKED} and submits the batch to
     * {@link ExtractionPipelineRunner#run(List)}.
     *
     * <p>Runs once at application startup (initialDelay = 0) and every 500 ms
     * thereafter. The SKIP LOCKED claim is atomic — concurrent scheduler threads
     * never process the same row twice.
     */
    @Scheduled(initialDelay = 0, fixedDelay = 500)
    void seedPendingExtractions() {
        int batchSize = reductoProperties.getBatchSize();
        List<ExtractionWithDocs> claimed = preconExtractionRepository.claimNextBatch(batchSize);

        if (claimed.isEmpty()) {
            log.debug("[ExtractionJobSeeder] No pending extractions found");
            return;
        }

        log.info("[ExtractionJobSeeder] Claimed {} extraction(s) — submitting to pipeline", claimed.size());
        pipelineRunner.run(claimed);
    }

    // ── Reaper ────────────────────────────────────────────────────────────────

    /**
     * Resets {@code PROCESSING} rows older than {@link ReductoProperties#getStaleMinutes()}
     * minutes back to {@code PENDING} so the next seeder cycle can retry them.
     *
     * <p>Delegates to the existing {@link PreconExtractionRepository#reapStaleExtractions(int)}
     * from TOS-37 — no new repository method is required.
     *
     * <p>Runs every 60 seconds.
     */
    @Scheduled(fixedRate = 60_000)
    void reapStuckJobs() {
        int staleMinutes = reductoProperties.getStaleMinutes();
        try {
            int reset = preconExtractionRepository.reapStaleExtractions(staleMinutes);
            if (reset > 0) {
                log.warn("[ExtractionJobSeeder] Reaped {} stuck PROCESSING extraction(s) back to PENDING", reset);
            } else {
                log.debug("[ExtractionJobSeeder] Reaper found no stuck extractions");
            }
        } catch (Exception e) {
            log.error("[ExtractionJobSeeder] {} — {}",
                    com.tosspaper.common.ApiErrorMessages.EXTRACTION_REAP_FAILED, e.getMessage(), e);
        }
    }
}
