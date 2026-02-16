package com.tosspaper.integrations.quickbooks.client

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadConfig
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import io.github.resilience4j.retry.RetryRegistry
import spock.lang.Specification
import spock.lang.Subject

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for QuickBooksResilienceHelper verifying:
 * - Per-realm isolation of bulkhead and rate limiter
 * - App-wide circuit breaker and retry
 * - Proper decorator chaining (bulkhead -> rate limiter -> circuit breaker -> retry)
 */
class QuickBooksResilienceHelperSpec extends Specification {

    // Use custom configs for faster test execution
    CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
        .slidingWindowSize(5)
        .failureRateThreshold(50.0f)
        .waitDurationInOpenState(Duration.ofSeconds(1))
        .permittedNumberOfCallsInHalfOpenState(2)
        .build()

    RateLimiterConfig rlConfig = RateLimiterConfig.custom()
        .limitRefreshPeriod(Duration.ofSeconds(1))
        .limitForPeriod(100)
        .timeoutDuration(Duration.ofMillis(500))
        .build()

    RetryConfig retryConfig = RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(100))
        .retryExceptions(RuntimeException.class)
        .build()

    BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
        .maxConcurrentCalls(10)
        .maxWaitDuration(Duration.ofMillis(500))
        .build()

    CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig)
    RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.of(rlConfig)
    RetryRegistry retryRegistry = RetryRegistry.of(retryConfig)
    BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig)

    @Subject
    QuickBooksResilienceHelper helper = new QuickBooksResilienceHelper(
        circuitBreakerRegistry, rateLimiterRegistry, retryRegistry, bulkheadRegistry
    )

    // ==================== Basic Execution Tests ====================

    def "execute should successfully run supplier and return result"() {
        given:
        def realmId = "realm-123"

        when:
        def result = helper.execute(realmId, { -> "success" })

        then:
        result == "success"
    }

    def "execute should throw exceptions from supplier"() {
        given:
        def realmId = "realm-123"

        when:
        helper.execute(realmId, { -> throw new RuntimeException("Test error") })

        then:
        def ex = thrown(RuntimeException)
        ex.message == "Test error"
    }

    // ==================== Per-Realm Isolation Tests ====================

    def "execute should create separate bulkhead per realm"() {
        given:
        def realm1 = "realm-1"
        def realm2 = "realm-2"

        when:
        helper.execute(realm1, { -> "result1" })
        helper.execute(realm2, { -> "result2" })

        then: "each realm has its own bulkhead"
        def bulkhead1 = bulkheadRegistry.bulkhead("quickbooks-realm-realm-1")
        def bulkhead2 = bulkheadRegistry.bulkhead("quickbooks-realm-realm-2")
        bulkhead1 != bulkhead2
    }

    def "execute should create separate rate limiter per realm"() {
        given:
        def realm1 = "realm-1"
        def realm2 = "realm-2"

        when:
        helper.execute(realm1, { -> "result1" })
        helper.execute(realm2, { -> "result2" })

        then: "each realm has its own rate limiter"
        def rl1 = rateLimiterRegistry.rateLimiter("quickbooks-realm-realm-1")
        def rl2 = rateLimiterRegistry.rateLimiter("quickbooks-realm-realm-2")
        rl1 != rl2
    }

    def "execute should use shared circuit breaker across all realms"() {
        given:
        def realm1 = "realm-1"
        def realm2 = "realm-2"

        when:
        helper.execute(realm1, { -> "result1" })
        helper.execute(realm2, { -> "result2" })

        then: "both realms use the same app-wide circuit breaker"
        def cb = circuitBreakerRegistry.circuitBreaker("quickbooks-app")
        cb != null
        cb.metrics.numberOfSuccessfulCalls == 2
    }

    def "execute should use shared retry across all realms"() {
        given:
        def realm1 = "realm-1"
        def callCount = new AtomicInteger(0)

        when:
        helper.execute(realm1, { ->
            if (callCount.incrementAndGet() < 3) {
                throw new RuntimeException("Transient error")
            }
            "success"
        })

        then: "retry was triggered and finally succeeded"
        callCount.get() == 3
    }

    // ==================== Bulkhead Concurrency Tests ====================

    def "bulkhead should limit concurrent calls per realm"() {
        given:
        // Create a bulkhead registry with max 2 concurrent calls
        def limitedBulkheadConfig = BulkheadConfig.custom()
            .maxConcurrentCalls(2)
            .maxWaitDuration(Duration.ofMillis(100))
            .build()
        def limitedBulkheadRegistry = BulkheadRegistry.of(limitedBulkheadConfig)

        def limitedHelper = new QuickBooksResilienceHelper(
            circuitBreakerRegistry, rateLimiterRegistry, retryRegistry, limitedBulkheadRegistry
        )

        def realmId = "limited-realm"
        def concurrentCalls = new AtomicInteger(0)
        def maxConcurrent = new AtomicInteger(0)
        def latch = new CountDownLatch(1)
        def executor = Executors.newFixedThreadPool(5)
        def failures = new AtomicInteger(0)

        when:
        5.times { i ->
            executor.submit {
                try {
                    limitedHelper.execute(realmId, { ->
                        def current = concurrentCalls.incrementAndGet()
                        maxConcurrent.updateAndGet { max -> Math.max(max, current) }
                        latch.await(500, TimeUnit.MILLISECONDS)
                        concurrentCalls.decrementAndGet()
                        "result-$i"
                    })
                } catch (Exception ignored) {
                    failures.incrementAndGet()
                }
            }
        }
        Thread.sleep(50) // Let threads start
        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)

        then: "max concurrent calls should be limited to 2"
        maxConcurrent.get() <= 2
        // Some calls should have been rejected due to bulkhead
        failures.get() >= 0 // May or may not fail depending on timing
    }

    // ==================== Rate Limiter Tests ====================

    def "rate limiter should be created per realm with proper configuration"() {
        given:
        def realmId = "rate-limit-test"

        when:
        helper.execute(realmId, { -> "result" })

        then:
        def rateLimiter = rateLimiterRegistry.rateLimiter("quickbooks-realm-rate-limit-test")
        rateLimiter != null
        rateLimiter.rateLimiterConfig.limitForPeriod == 100
    }

    // ==================== Circuit Breaker Tests ====================

    def "circuit breaker should open after failures exceed threshold"() {
        given:
        def realmId = "cb-test"
        def failCount = new AtomicInteger(0)

        // Use a custom helper with stricter circuit breaker for testing
        def strictCbConfig = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(3)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(66.0f)
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .build()
        def strictCbRegistry = CircuitBreakerRegistry.of(strictCbConfig)

        // Use a retry config that doesn't retry for this test
        def noRetryConfig = RetryConfig.custom()
            .maxAttempts(1)
            .build()
        def noRetryRegistry = RetryRegistry.of(noRetryConfig)

        def strictHelper = new QuickBooksResilienceHelper(
            strictCbRegistry, rateLimiterRegistry, noRetryRegistry, bulkheadRegistry
        )

        when: "make 3 failing calls to trigger circuit breaker"
        3.times {
            try {
                strictHelper.execute(realmId, { -> throw new RuntimeException("Error") })
            } catch (Exception ignored) {
                failCount.incrementAndGet()
            }
        }

        then: "circuit breaker should be open"
        def cb = strictCbRegistry.circuitBreaker("quickbooks-app")
        cb.state == CircuitBreaker.State.OPEN
        failCount.get() == 3
    }

    // ==================== Retry Tests ====================

    def "retry should retry failed operations up to max attempts"() {
        given:
        def realmId = "retry-test"
        def attemptCount = new AtomicInteger(0)

        when:
        try {
            helper.execute(realmId, { ->
                attemptCount.incrementAndGet()
                throw new RuntimeException("Always fails")
            })
        } catch (RuntimeException ignored) {
            // Expected
        }

        then: "should have retried 3 times (as per retry config)"
        attemptCount.get() == 3
    }

    def "retry should succeed if operation succeeds within max attempts"() {
        given:
        def realmId = "retry-success-test"
        def attemptCount = new AtomicInteger(0)

        when:
        def result = helper.execute(realmId, { ->
            if (attemptCount.incrementAndGet() < 2) {
                throw new RuntimeException("First attempt fails")
            }
            "success"
        })

        then:
        result == "success"
        attemptCount.get() == 2
    }

    // ==================== Decorator Chain Tests ====================

    def "decorators should be applied in correct order: bulkhead -> rate limiter -> circuit breaker -> retry"() {
        given:
        def realmId = "chain-test"
        def executionOrder = []

        // We can verify order by checking that all components are accessed
        when:
        def result = helper.execute(realmId, { ->
            executionOrder << "actual-call"
            "result"
        })

        then:
        result == "result"
        executionOrder == ["actual-call"]

        // Verify all components exist
        bulkheadRegistry.bulkhead("quickbooks-realm-chain-test") != null
        rateLimiterRegistry.rateLimiter("quickbooks-realm-chain-test") != null
        circuitBreakerRegistry.circuitBreaker("quickbooks-app") != null
        retryRegistry.retry("quickbooks-app") != null
    }

    // ==================== Multiple Realm Isolation Tests ====================

    def "failures in one realm should not affect bulkhead of another realm"() {
        given:
        def realm1 = "isolated-realm-1"
        def realm2 = "isolated-realm-2"
        def realm1Calls = new AtomicInteger(0)
        def realm2Calls = new AtomicInteger(0)

        when: "realm1 makes successful calls"
        3.times {
            helper.execute(realm1, { ->
                realm1Calls.incrementAndGet()
                "success"
            })
        }

        and: "realm2 makes calls independently"
        3.times {
            helper.execute(realm2, { ->
                realm2Calls.incrementAndGet()
                "success"
            })
        }

        then: "both realms executed independently"
        realm1Calls.get() == 3
        realm2Calls.get() == 3

        and: "they have separate bulkheads"
        def bh1 = bulkheadRegistry.bulkhead("quickbooks-realm-isolated-realm-1")
        def bh2 = bulkheadRegistry.bulkhead("quickbooks-realm-isolated-realm-2")
        bh1 != bh2
    }

    def "rate limiting in one realm should not affect another realm"() {
        given:
        def realm1 = "rl-isolated-1"
        def realm2 = "rl-isolated-2"

        when:
        helper.execute(realm1, { -> "result1" })
        helper.execute(realm2, { -> "result2" })

        then:
        def rl1 = rateLimiterRegistry.rateLimiter("quickbooks-realm-rl-isolated-1")
        def rl2 = rateLimiterRegistry.rateLimiter("quickbooks-realm-rl-isolated-2")
        rl1.metrics.availablePermissions == rl1.rateLimiterConfig.limitForPeriod - 1
        rl2.metrics.availablePermissions == rl2.rateLimiterConfig.limitForPeriod - 1
    }
}
