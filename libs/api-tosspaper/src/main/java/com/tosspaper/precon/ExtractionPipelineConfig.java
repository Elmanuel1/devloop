package com.tosspaper.precon;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Provides infrastructure beans for the extraction pipeline scheduler.
 *
 * <p>Registers a fixed-size {@link ThreadPoolTaskExecutor} that processes
 * individual extraction records dispatched by {@link ExtractionPollJob}.
 * Keeping the executor separate from the scheduler thread ensures the poll
 * cycle is never blocked by slow per-record work.
 */
@Configuration
public class ExtractionPipelineConfig {

    /**
     * Fixed-size executor for processing individual extraction records.
     *
     * <p>Pool size is configurable via {@code extraction.processing.thread-pool-size}
     * (default 5). Both core and max are the same value so the pool is truly fixed —
     * no dynamic growth that could exhaust resources under backpressure.
     *
     * @param poolSize number of worker threads
     * @return initialised executor
     */
    @Bean("extractionProcessingExecutor")
    public Executor extractionProcessingExecutor(
            @Value("${extraction.processing.thread-pool-size:5}") int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setThreadNamePrefix("extraction-worker-");
        executor.initialize();
        return executor;
    }
}
