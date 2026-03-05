package com.tosspaper.precon;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Spring configuration for the extraction processing pipeline.
 *
 * <p>Exposes a single bean: a bounded virtual-thread executor used by
 * {@link ExtractionPipelineRunner} to fan out per-document Reducto calls.
 *
 * <h3>Why bounded?</h3>
 * <p>Unbounded {@code supplyAsync} (no executor) would allow an unlimited number
 * of in-flight Reducto calls. The owner specified a configurable upper bound
 * (default 5) so that load on the external Reducto API is predictable and the
 * service degrades gracefully under volume spikes.
 *
 * <h3>Why virtual threads?</h3>
 * <p>Each slot is a virtual thread, so blocking I/O inside
 * {@link ExtractionPipelineRunner#callReducto(String)} does not pin an OS
 * carrier thread. The bound controls Reducto concurrency, not OS-thread usage.
 */
@Configuration
public class ExtractionPipelineConfig {

    /**
     * Returns a fixed-size pool of virtual threads for document extraction.
     *
     * <p>Pool size is read from {@link ExtractionProcessingProperties#getThreadPoolSize()},
     * which defaults to {@code 5} and can be overridden via
     * {@code extraction.processing.thread-pool-size} in {@code application.yml}.
     *
     * @param props configuration properties
     * @return bounded virtual-thread executor
     */
    @Bean
    public Executor extractionProcessingExecutor(ExtractionProcessingProperties props) {
        return Executors.newFixedThreadPool(
                props.getThreadPoolSize(),
                Thread.ofVirtual().factory()
        );
    }
}
