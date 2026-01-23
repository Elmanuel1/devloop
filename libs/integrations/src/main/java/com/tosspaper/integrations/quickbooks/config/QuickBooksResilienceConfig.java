package com.tosspaper.integrations.quickbooks.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration for Resilience4j patterns (Circuit Breaker, Rate Limiter, Retry, Bulkhead).
 * Applies specifically to QuickBooks integration to handle API limits and failures.
 *
 * QuickBooks API Limits:
 * - 500 requests per minute per realm
 * - 10 concurrent connections per realm
 * - Batch operations support up to 30 entities per request
 */
@Configuration
@RequiredArgsConstructor
public class QuickBooksResilienceConfig {

    private final QuickBooksProperties properties;

    /**
     * Maximum entities per batch request (QuickBooks limit is 30).
     */
    public static final int MAX_BATCH_SIZE = 30;

    @Bean
    public CircuitBreakerConfig quickBooksCircuitBreakerConfig() {
        QuickBooksProperties.CircuitBreaker cb = properties.getResilience().getCircuitBreaker();
        CircuitBreakerConfig.SlidingWindowType windowType = "COUNT_BASED".equalsIgnoreCase(cb.getSlidingWindowType())
                ? CircuitBreakerConfig.SlidingWindowType.COUNT_BASED
                : CircuitBreakerConfig.SlidingWindowType.TIME_BASED;
        
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(cb.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(cb.getWaitDurationSeconds()))
                .slidingWindowType(windowType)
                .slidingWindowSize(cb.getSlidingWindowSize())
                .permittedNumberOfCallsInHalfOpenState(cb.getPermittedCallsInHalfOpen())
                .recordExceptions(
                        com.intuit.ipp.exception.FMSException.class,
                        org.springframework.web.client.HttpServerErrorException.class,
                        org.springframework.web.client.HttpClientErrorException.class
                )
                .build();
    }

    @Bean
    public CircuitBreakerRegistry quickBooksCircuitBreakerRegistry(CircuitBreakerConfig config) {
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public RateLimiterConfig quickBooksRateLimiterConfig() {
        QuickBooksProperties.RateLimiter rl = properties.getResilience().getRateLimiter();
        return RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(rl.getLimitRefreshPeriodSeconds()))
                .limitForPeriod(rl.getLimitPerMinute())
                .timeoutDuration(Duration.ofMillis(rl.getTimeoutMillis()))
                .build();
    }


    @Bean
    public RetryConfig quickBooksRetryConfig() {
        QuickBooksProperties.Retry retry = properties.getResilience().getRetry();
        return RetryConfig.custom()
                .maxAttempts(retry.getMaxAttempts())
                .waitDuration(Duration.ofMillis(retry.getWaitMillis()))
                .retryExceptions(
                        com.intuit.ipp.exception.FMSException.class,
                        org.springframework.web.client.HttpServerErrorException.class,
                        org.springframework.web.client.HttpClientErrorException.TooManyRequests.class
                )
                .build();
    }

    @Bean
    public RetryRegistry quickBooksRetryRegistry(RetryConfig config) {
        return RetryRegistry.of(config);
    }

    /**
     * Default bulkhead config template for per-realm instances.
     * Dynamic instances will be created with this config.
     */
    @Bean
    public BulkheadConfig quickBooksBulkheadConfig() {
        QuickBooksProperties.Bulkhead bulkhead = properties.getResilience().getBulkhead();
        return BulkheadConfig.custom()
                .maxConcurrentCalls(bulkhead.getMaxConcurrentCalls())
                .maxWaitDuration(Duration.ofSeconds(bulkhead.getMaxWaitDurationSeconds()))
                .build();
    }

    @Bean
    public BulkheadRegistry quickBooksBulkheadRegistry(BulkheadConfig bulkheadConfig) {
        return BulkheadRegistry.of(bulkheadConfig);
    }

    @Bean
    public RateLimiterRegistry quickBooksRateLimiterRegistry(RateLimiterConfig config) {
        return RateLimiterRegistry.of(config);
    }
}

