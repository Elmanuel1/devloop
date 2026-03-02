package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Scheduled job that polls for {@code PENDING} extractions and dispatches
 * them to the fixed processing thread pool.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li><strong>Fixed scheduler thread.</strong> Spring's {@code @Scheduled}
 *       runs this method on a dedicated thread from the shared task scheduler.
 *       Only one execution is in-flight per JVM at a time.</li>
 *   <li><strong>Distributed lock.</strong> {@link ExtractionPipelineLockService}
 *       wraps each poll in a Redisson lock so that across a multi-instance
 *       cluster only ONE node picks up work per cycle. If the lock is already
 *       held the current execution is a no-op — no blocking wait.</li>
 *   <li><strong>Fixed batch size.</strong> At most {@link #POLL_BATCH_SIZE}
 *       rows are fetched per cycle, keeping DB load and transaction scope
 *       predictable regardless of queue depth.</li>
 *   <li><strong>Fixed processing thread pool.</strong> Each record is
 *       dispatched asynchronously to the named {@code extractionProcessingExecutor}
 *       so the scheduler thread returns immediately and is never blocked by
 *       slow per-record work.</li>
 * </ul>
 *
 * <p>TODO [TOS-38] Replace {@link #processRecord} stub with the real pipeline
 * worker once the extraction engine is wired.
 */
@Slf4j
@Component
public class ExtractionPollJob {

    /** Maximum number of PENDING rows fetched in a single poll cycle. */
    static final int POLL_BATCH_SIZE = 50;

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineLockService lockService;
    private final Executor extractionProcessingExecutor;

    public ExtractionPollJob(
            PreconExtractionRepository preconExtractionRepository,
            ExtractionPipelineLockService lockService,
            @Qualifier("extractionProcessingExecutor") Executor extractionProcessingExecutor) {
        this.preconExtractionRepository = preconExtractionRepository;
        this.lockService = lockService;
        this.extractionProcessingExecutor = extractionProcessingExecutor;
    }

    /**
     * Polls for pending extractions and dispatches each to the processing pool.
     *
     * <p>Runs on a fixed delay — the next execution starts
     * {@code extraction.poll.delay-ms} milliseconds <em>after</em> the
     * previous one finishes, preventing pile-up when processing is slow.
     * An initial delay of {@code extraction.poll.initial-delay-ms}
     * (default 10 s) gives the application context time to fully start
     * before the first poll.
     */
    @Scheduled(
            fixedDelayString  = "${extraction.poll.delay-ms:5000}",
            initialDelayString = "${extraction.poll.initial-delay-ms:10000}"
    )
    public void poll() {
        lockService.tryRunExclusive(() -> {
            List<ExtractionsRecord> pending =
                    preconExtractionRepository.findPendingExtractions(POLL_BATCH_SIZE);

            if (pending.isEmpty()) {
                log.debug("[ExtractionPoll] No pending extractions found");
                return;
            }

            log.info("[ExtractionPoll] Found {} pending extraction(s) to process", pending.size());
            pending.forEach(record ->
                    extractionProcessingExecutor.execute(() -> processRecord(record)));
        });
    }

    /**
     * Hands an individual extraction record off to the pipeline worker.
     *
     * <p>Runs on a thread from {@code extractionProcessingExecutor} — never
     * on the scheduler thread. TODO [TOS-38] replace with real pipeline worker.
     *
     * @param record the PENDING extraction to process
     */
    private void processRecord(ExtractionsRecord record) {
        // TODO [TOS-38] wire to ExtractionPipelineWorker
        log.info("[ExtractionPoll] Processing extraction {}", record.getId());
    }
}
