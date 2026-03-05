package com.tosspaper.precon;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe, startup-validated configuration for the extraction processing pipeline.
 *
 * <p>Bound to the {@code extraction.processing.*} namespace. Override in tests via
 * {@code @TestPropertySource} or a dedicated {@code application-test.properties}.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * extraction:
 *   processing:
 *     thread-pool-size: 5
 *     batch-size: 20
 *     stale-minutes: 15
 * </pre>
 */
@ConfigurationProperties(prefix = "extraction.processing")
@Validated
public class ExtractionProcessingProperties {

    /**
     * Maximum number of concurrent virtual threads used for document processing.
     * Defaults to {@code 5}.
     */
    @Positive
    private int threadPoolSize = 5;

    /**
     * Maximum number of {@code PENDING} extractions claimed per poll cycle.
     * Defaults to {@code 20}.
     */
    @Positive
    private int batchSize = 20;

    /**
     * Age threshold in minutes: {@code PROCESSING} extractions older than this
     * are reset to {@code PENDING} by the reaper. Defaults to {@code 15}.
     */
    @Positive
    private int staleMinutes = 15;

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getStaleMinutes() {
        return staleMinutes;
    }

    public void setStaleMinutes(int staleMinutes) {
        this.staleMinutes = staleMinutes;
    }
}
