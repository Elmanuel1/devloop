package com.tosspaper.integrations.quickbooks.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Helper for applying resilience patterns to QuickBooks API calls.
 * Uses per-realm rate limiters and bulkheads to isolate QBO companies.
 *
 * <p><b>Tenancy Isolation:</b>
 * The Resilience4j Registry maintains a thread-safe map of instances by name.
 * Each realm gets its own isolated instance with separate state:
 * <ul>
 *   <li>RateLimiter: Separate request counter per realm (500/min per realm)</li>
 *   <li>Bulkhead: Separate semaphore/slots per realm (10 concurrent per realm)</li>
 * </ul>
 * Instances are created on-demand and cached by the registry. All instances share
 * the same configuration but maintain isolated state.
 *
 * <p>Resilience stack (outer to inner):
 * <ol>
 *   <li>Bulkhead (10 concurrent) - limits concurrent connections per realm</li>
 *   <li>Rate Limiter (500/min) - limits request rate per realm</li>
 *   <li>Circuit Breaker - prevents cascading failures (app-wide)</li>
 *   <li>Retry (3 attempts) - retries transient failures (app-wide)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickBooksResilienceHelper {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final RetryRegistry retryRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    // Per-realm resilience instance prefix
    // Each realm gets unique instance: "quickbooks-realm-123", "quickbooks-realm-456", etc.
    private static final String REALM_PREFIX = "quickbooks-realm";
    // App-level (shared) instance name for retry/circuit breaker
    private static final String APP_NAME = "quickbooks-app";

    /**
     * Execute a supplier with resilience patterns (Bulkhead, Rate Limiter, Circuit Breaker, Retry).
     * Bulkhead and Rate limiter are per-realm to respect QBO's per-company limits.
     * Circuit breaker and retry are app-wide.
     *
     * <p><b>Tenancy:</b> Each realm gets its own Bulkhead and RateLimiter instance
     * with isolated state. The registry creates/retrieves instances by name, ensuring
     * complete isolation between realms while sharing the same configuration.
     *
     * @param realmId the QuickBooks company ID (used for per-realm bulkhead/rate limiting)
     * @param supplier the API call to execute
     * @param <T> return type
     * @return result of the execution
     */
    public <T> T execute(String realmId, Supplier<T> supplier) {
        // Per-realm bulkhead and rate limiter (QBO has per-company limits)
        // Registry maintains thread-safe map: each realm gets isolated instance
        // Instance name: "quickbooks-realm-{realmId}" ensures complete isolation
        String realmKey = REALM_PREFIX + "-" + realmId;
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(realmKey);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(realmKey);

        // App-wide circuit breaker and retry
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(APP_NAME);
        Retry retry = retryRegistry.retry(APP_NAME);

        // Decorate supplier: Bulkhead -> Rate Limiter -> Circuit Breaker -> Retry -> actual call
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead,
            RateLimiter.decorateSupplier(rateLimiter,
                CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry, supplier)
                )
            )
        );

        return decorated.get();
    }
}
