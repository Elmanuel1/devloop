package com.tosspaper.precon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Registers the shared extraction queue as a Spring bean.
 *
 * <p>The queue is unbounded ({@link LinkedBlockingQueue} with no capacity cap)
 * and is shared by the seeder, webhook controller, and worker within a single JVM.
 *
 * <p><strong>SINGLE-INSTANCE SCOPE.</strong> {@link LinkedBlockingQueue} is an
 * in-memory, per-JVM structure scoped to this PR. In a multi-instance deployment
 * each JVM has its own isolated queue: webhook callbacks received by instance A
 * are invisible to instance B's worker, and startup recovery re-enqueues the same
 * PENDING rows into every running instance simultaneously, causing duplicate
 * processing.
 *
 * <p><strong>Distributed lock mitigation.</strong> {@link ExtractionPipelineLockService}
 * wraps the extraction worker execution with a Redisson distributed lock so that only
 * one JVM across the cluster processes the queue at a time, preventing duplicate runs.
 *
 * <p><strong>Follow-up.</strong> TOS-38/TOS-39 will replace this bean with a
 * Redis Streams or SQS adapter. The injection point is the {@link BlockingQueue}
 * interface — all producers and consumers require zero changes.
 */
@Slf4j
@Configuration
public class ExtractionQueueConfig {

    /**
     * Unbounded blocking queue that carries {@link ExtractionContext} items
     * from producers (seeder / webhook controller) to the extraction worker.
     *
     * @return a new empty {@link LinkedBlockingQueue}
     */
    @Bean
    public BlockingQueue<ExtractionContext> extractionQueue() {
        return new LinkedBlockingQueue<>();
    }

    /**
     * Emits a startup warning so that multi-instance deployments immediately see
     * this limitation in logs. Remove once a distributed queue replaces this bean.
     *
     * <p>Uses {@link EventListener} on {@link ApplicationReadyEvent} so the warning
     * fires after the full application context is ready, avoiding {@code @PostConstruct}
     * which runs before the context is fully initialised.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warnSingleInstanceQueue() {
        log.warn(
                "[ExtractionQueue] Using in-memory LinkedBlockingQueue — " +
                "NOT safe for multi-instance deployment. " +
                "Replace with a Redis Streams or SQS adapter before scaling out."
        );
    }
}
