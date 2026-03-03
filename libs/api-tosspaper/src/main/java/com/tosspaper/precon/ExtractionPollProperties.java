package com.tosspaper.precon;

import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe, startup-validated configuration for the extraction poll scheduler.
 *
 * <p>Bound to the {@code extraction.poll.*} namespace. Override in tests via
 * {@code @TestPropertySource} or a dedicated {@code application-test.properties}
 * without any {@code @Value} / property-source hacks.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * extraction:
 *   poll:
 *     delay-ms: 5000
 * </pre>
 */
@ConfigurationProperties(prefix = "extraction.poll")
@Data
@Validated
public class ExtractionPollProperties {

    /**
     * Fixed delay in milliseconds between poll cycles.
     *
     * <p>The scheduler uses {@code scheduleWithFixedDelay}, so each cycle
     * starts only after the previous one finishes. Must be a positive value.
     *
     * <p>Defaults to {@code 5000} ms (5 seconds).
     */
    @Positive
    private long delayMs = 5_000;
}
