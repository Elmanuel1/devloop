package com.tosspaper.precon;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe, startup-validated configuration for the extraction processing executor.
 *
 * <p>Bound to the {@code extraction.processing.*} namespace. Override in tests via
 * {@code @TestPropertySource} or a dedicated {@code application-test.properties}
 * without any {@code @Value} / property-source hacks.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * extraction:
 *   processing:
 *     thread-pool-size: 5
 * </pre>
 */
@ConfigurationProperties(prefix = "extraction.processing")
@Data
@Validated
public class ExtractionProcessingProperties {

    /**
     * Number of worker threads in the fixed extraction processing pool.
     *
     * <p>Both core and max pool size are set to this value so the pool is
     * truly fixed — no dynamic growth that could exhaust resources under
     * backpressure. Must be a positive value.
     *
     * <p>Defaults to {@code 5}.
     */
    @Positive
    private int threadPoolSize = 5;
}
