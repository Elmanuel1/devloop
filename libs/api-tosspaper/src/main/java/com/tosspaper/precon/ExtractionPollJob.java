package com.tosspaper.precon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle-managed scheduler that polls for {@code PENDING} extractions and
 * hands each one to {@link ExtractionPipelineRunner} for processing.
 *
 * <h3>Single responsibility</h3>
 * <p>This class owns exactly one concern: <em>when</em> to poll.
 * The <em>how</em> (acquire lock, mark as processing, scatter-gather)
 * lives in {@link ExtractionPipelineRunner}.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li><strong>ScheduledExecutorService.</strong> A single-threaded
 *       {@link ScheduledExecutorService} drives the poll cycle using
 *       {@code scheduleWithFixedDelay} so consecutive runs never overlap
 *       and the next cycle starts only after the previous one finishes.</li>
 *   <li><strong>SmartLifecycle.</strong> Keeps lifecycle management explicit
 *       and testable — no {@code @PostConstruct} or Spring
 *       {@code @Scheduled}.</li>
 *   <li><strong>No global lock.</strong> Every JVM instance polls at the
 *       same cadence. Duplicate processing is prevented by the per-extraction
 *       lock inside {@link ExtractionPipelineRunner}.</li>
 *   <li><strong>Fixed batch size.</strong> At most {@link #POLL_BATCH_SIZE}
 *       rows are fetched per cycle, keeping DB load predictable regardless
 *       of queue depth.</li>
 * </ul>
 */
@Slf4j
@Component
public class ExtractionPollJob implements SmartLifecycle {

    /** Maximum number of PENDING rows fetched in a single poll cycle. */
    static final int POLL_BATCH_SIZE = 50;

    private final PreconExtractionRepository preconExtractionRepository;
    private final ExtractionPipelineRunner pipelineRunner;
    private final long delayMs;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "extraction-poll"));

    private volatile boolean running = false;

    public ExtractionPollJob(
            PreconExtractionRepository preconExtractionRepository,
            ExtractionPipelineRunner pipelineRunner,
            @Value("${extraction.poll.delay-ms:5000}") long delayMs) {
        this.preconExtractionRepository = preconExtractionRepository;
        this.pipelineRunner = pipelineRunner;
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
     * Polls for pending extractions and dispatches each one to
     * {@link ExtractionPipelineRunner#execute(ExtractionWithDocs)}.
     *
     * <p>No global lock — every instance polls freely. Per-extraction locks
     * inside {@link ExtractionPipelineRunner} prevent duplicate work.
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
            pipelineRunner.execute(extraction);
        }
    }
}
