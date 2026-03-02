package com.tosspaper.precon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Provides a Redisson distributed lock for the extraction pipeline.
 *
 * <p>Only ONE instance across the cluster may hold the lock at a time,
 * preventing duplicate extraction job execution in multi-instance deployments.
 *
 * <p>The lock is non-blocking: if another JVM currently holds it the action
 * is skipped immediately to avoid queue starvation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionPipelineLockService {

    static final String EXTRACTION_PIPELINE_LOCK_KEY = "extraction:pipeline:lock";
    private static final long LOCK_WAIT_SECONDS = 0L;
    private static final long LOCK_LEASE_SECONDS = 30L;

    private final RedissonClient redissonClient;

    /**
     * Executes the given action under the distributed extraction pipeline lock.
     *
     * <p>If another JVM instance currently holds the lock the action is skipped
     * (no blocking wait) to avoid queue starvation.
     *
     * @param action the action to run exclusively
     * @return {@code true} if the action was executed, {@code false} if the lock was not acquired
     */
    public boolean tryRunExclusive(Runnable action) {
        RLock lock = redissonClient.getLock(EXTRACTION_PIPELINE_LOCK_KEY);
        try {
            boolean acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[ExtractionPipeline] Distributed lock not acquired — another instance is processing");
                return false;
            }
            log.debug("[ExtractionPipeline] Distributed lock acquired");
            action.run();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[ExtractionPipeline] Interrupted while trying to acquire distributed lock", e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("[ExtractionPipeline] Distributed lock released");
            }
        }
    }
}
