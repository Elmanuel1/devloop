package com.tosspaper.precon;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe, startup-validated configuration for the extraction processing executor.
 *
 * <p>Bound to the {@code extraction.processing.*} namespace. Override in tests via
 * {@code @TestPropertySource} or a dedicated {@code application-test.properties}.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * extraction:
 *   processing:
 *     thread-pool-size: 5
 * </pre>
 */
@ConfigurationProperties(prefix = "extraction.processing")
@Validated
public class ExtractionProcessingProperties {

    /**
     * Maximum number of concurrent virtual threads used for document processing.
     *
     * <p>Each slot is a virtual thread, so blocking I/O (Reducto HTTP calls)
     * does not consume OS threads. Keep this small to bound in-flight Reducto
     * requests to a predictable level.
     *
     * <p>Defaults to {@code 5}.
     */
    @Positive
    private int threadPoolSize = 5;

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }
}
