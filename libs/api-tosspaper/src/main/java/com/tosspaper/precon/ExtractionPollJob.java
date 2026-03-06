package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Scheduled job that claims pending extractions and fans them out for processing. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionPollJob {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineRunner pipelineRunner;
    private final ExtractionProcessingProperties processingProperties;

    // ── Poll ──────────────────────────────────────────────────────────────────

    /** Claims the next batch of pending extractions and submits to the pipeline. */
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

    /** Resets stale processing extractions back to pending. */
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
