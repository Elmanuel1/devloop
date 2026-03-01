package com.tosspaper.precon;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Registers the shared extraction queue as a Spring bean.
 *
 * The queue is unbounded ({@link LinkedBlockingQueue} with no capacity cap)
 * and is shared by the seeder, webhook controller, and worker.
 */
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
}
