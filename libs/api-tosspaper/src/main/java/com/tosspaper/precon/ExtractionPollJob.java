package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle-managed job that polls for {@code PENDING} extractions and
 * dispatches them to the fixed processing thread pool.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li><strong>ScheduledExecutorService.</strong> A single-threaded
 *       {@link ScheduledExecutorService} runs the poll task with
 *       {@code scheduleWithFixedDelay} so that consecutive runs never
 *       overlap, and the next cycle starts only after the previous one
 *       finishes.</li>
 *   <li><strong>SmartLifecycle.</strong> The executor is started in
 *       {@link #start()} and shut down gracefully in {@link #stop()},
 *       keeping lifecycle management explicit and testable without
 *       relying on {@code @PostConstruct} or Spring's {@code @Scheduled}
 *       task scheduler.</li>
 *   <li><strong>Distributed lock.</strong> {@link ExtractionPipelineLockService}
 *       wraps each poll in a Redisson lock so that across a multi-instance
 *       cluster only ONE node picks up work per cycle. If the lock is
 *       already held the current execution is a no-op — no blocking wait.</li>
 *   <li><strong>Mark PROCESSING before dispatch.</strong> Each record is
 *       transitioned to {@code processing} status in the scheduler thread
 *       before being handed off to the processing pool, preventing a
 *       subsequent poll cycle from re-fetching the same record as
 *       {@code pending}.</li>
 *   <li><strong>Fixed batch size.</strong> At most {@link #POLL_BATCH_SIZE}
 *       rows are fetched per cycle, keeping DB load predictable regardless
 *       of queue depth.</li>
 * </ul>
 *
 * <p>TODO [TOS-38] Replace {@link #processRecord} stub with the real pipeline
 * worker once the extraction engine is wired.
 */
@Slf4j
@Component
public class ExtractionPollJob implements SmartLifecycle {

    /** Maximum number of PENDING rows fetched in a single poll cycle. */
    static final int POLL_BATCH_SIZE = 50;

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineLockService lockService;
    private final Executor extractionProcessingExecutor;
    private final long delayMs;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "extraction-poll"));

    private volatile boolean running = false;

    public ExtractionPollJob(
            PreconExtractionRepository preconExtractionRepository,
            ExtractionPipelineLockService lockService,
            @Qualifier("extractionProcessingExecutor") Executor extractionProcessingExecutor,
            @Value("${extraction.poll.delay-ms:5000}") long delayMs) {
        this.preconExtractionRepository = preconExtractionRepository;
        this.lockService = lockService;
        this.extractionProcessingExecutor = extractionProcessingExecutor;
        this.delayMs = delayMs;
    }

    // ---- SmartLifecycle ----

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

    // ---- Poll logic ----

    /**
     * Polls for pending extractions, marks each as PROCESSING in the
     * scheduler thread, then dispatches each to the processing pool.
     *
     * <p>Wrapped in a Redisson distributed lock so that only one JVM
     * instance executes the poll cycle at a time.
     */
    void poll() {
        lockService.tryRunExclusive(() -> {
            List<ExtractionsRecord> pending =
                    preconExtractionRepository.findPendingExtractions(POLL_BATCH_SIZE);

            if (pending.isEmpty()) {
                log.debug("[ExtractionPoll] No pending extractions found");
                return;
            }

            log.info("[ExtractionPoll] Found {} pending extraction(s) to process", pending.size());
            for (ExtractionsRecord record : pending) {
                // Mark PROCESSING in the scheduler thread before dispatching
                // so the next poll cycle does not re-fetch the same record.
                preconExtractionRepository.markAsProcessing(record.getId());
                extractionProcessingExecutor.execute(() -> processRecord(record));
            }
        });
    }

    /**
     * Hands an individual extraction record off to the pipeline worker.
     *
     * <p>Runs on a thread from {@code extractionProcessingExecutor} — never
     * on the scheduler thread. TODO [TOS-38] replace with real pipeline worker.
     *
     * @param record the extraction record to process
     */
    private void processRecord(ExtractionsRecord record) {
        // TODO [TOS-38] wire to ExtractionPipelineWorker
        log.info("[ExtractionPoll] Processing extraction {}", record.getId());
    }
}
