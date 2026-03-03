package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Provides Redisson distributed locks for the extraction pipeline.
 *
 * <h3>Lock levels</h3>
 * <ul>
 *   <li><strong>Per-extraction lock</strong> (primary) — keyed by extraction ID
 *       ({@code extraction:lock:{extractionId}}). Acquired for each extraction
 *       fetched from the pending queue. A 20-minute lease gives the pipeline
 *       enough time to scatter documents, gather results, and save.
 *       Non-blocking: if another JVM holds the lock the extraction is skipped.</li>
 * </ul>
 *
 * <p>No global pipeline lock is used — every node polls and races per-extraction.
 * The per-ID lock prevents duplicate processing of the same extraction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionPipelineLockService {

    /** Key prefix used for per-extraction locks. */
    static final String EXTRACTION_LOCK_PREFIX = "extraction:lock:";

    /** Total lease duration for a per-extraction lock in minutes. */
    static final long EXTRACTION_LOCK_LEASE_MINUTES = 20L;

    private static final long LOCK_WAIT_SECONDS = 0L;

    private final RedissonClient redissonClient;

    /**
     * Attempts to acquire a per-extraction distributed lock and, if successful,
     * runs the given action.
     *
     * <p>The lock key is {@code extraction:lock:{extractionId}}. The lease is
     * 20 minutes — long enough for scatter-gather to complete under worst-case
     * load. The call is non-blocking: if another JVM already holds the lock the
     * action is skipped immediately.
     *
     * @param extractionId the extraction to lock
     * @param action       the action to run while holding the lock
     * @return {@code true} if the lock was acquired and the action ran;
     *         {@code false} if the lock was not acquired or the thread was interrupted
     */
    public boolean tryWithExtractionLock(String extractionId, Runnable action) {
        String lockKey = EXTRACTION_LOCK_PREFIX + extractionId;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_SECONDS, EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES);
            if (!acquired) {
                log.debug("[ExtractionPipeline] Per-extraction lock not acquired for {} — another instance is processing",
                        extractionId);
                return false;
            }
            log.debug("[ExtractionPipeline] Per-extraction lock acquired for {}", extractionId);
            action.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ExtractionPipeline] Interrupted while acquiring lock for extraction {}", extractionId, e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[ExtractionPipeline] Per-extraction lock released for {}", extractionId);
            }
        }
    }

    /**
     * Releases the per-extraction lock if it is still held by the current thread.
     *
     * <p>Safe to call from a {@code finally} block — it is a no-op when the lock
     * is not held (e.g. lease already expired, or lock was never acquired).
     *
     * @param extractionId the extraction whose lock should be released
     */
    public void releaseLock(String extractionId) {
        String lockKey = EXTRACTION_LOCK_PREFIX + extractionId;
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("[ExtractionPipeline] Per-extraction lock explicitly released for {}", extractionId);
        }
    }
}
