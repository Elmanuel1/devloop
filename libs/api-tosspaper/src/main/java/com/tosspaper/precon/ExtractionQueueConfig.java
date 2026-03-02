package com.tosspaper.precon;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Registers the shared extraction queue as a Spring bean.
 *
 * <p>The queue is unbounded ({@link LinkedBlockingQueue} with no capacity cap)
 * and is shared by the seeder, webhook controller, and worker.
 *
 * <p><strong>⚠ SINGLE-INSTANCE ONLY.</strong> {@link LinkedBlockingQueue} is an
 * in-memory, per-JVM structure. In a multi-instance deployment each JVM has its own
 * isolated queue: webhook callbacks received by instance A are invisible to instance
 * B's worker, and startup recovery re-enqueues the same PENDING rows into every
 * running instance simultaneously, causing duplicate processing.
 *
 * <p>Before scaling to multiple instances this bean must be replaced with a distributed
 * queue adapter. The project already has {@code MessagingProperties} (redis/sqs switch),
 * {@code RedisStreamsProperties}, and {@code SqsProperties} — the follow-up PR wires
 * {@link ExtractionContext} events into that infrastructure. The injection point is
 * the {@link BlockingQueue} interface: all producers and consumers require zero changes.
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
     */
    @PostConstruct
    void warnSingleInstanceQueue() {
        log.warn(
                "[ExtractionQueue] Using in-memory LinkedBlockingQueue — " +
                "NOT safe for multi-instance deployment. " +
                "Replace with a Redis Streams or SQS adapter before scaling out."
        );
    }
}
