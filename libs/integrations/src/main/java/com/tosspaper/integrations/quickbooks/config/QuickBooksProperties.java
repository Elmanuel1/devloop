package com.tosspaper.integrations.quickbooks.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for QuickBooks Online integration.
 */
@ConfigurationProperties(prefix = "app.integrations.quickbooks")
@Data
@Validated
public class QuickBooksProperties {

    /**
     * QuickBooks OAuth2 client ID (required).
     */
    @NotBlank(message = "QuickBooks client ID is required")
    private String clientId;

    /**
     * QuickBooks OAuth2 client secret (required).
     */
    @NotBlank(message = "QuickBooks client secret is required")
    private String clientSecret;

    /**
     * OAuth2 redirect URI for callback after authorization (required).
     */
    @NotBlank(message = "QuickBooks redirect URI is required")
    private String redirectUri;

    /**
     * OpenID Connect discovery document URL (required).
     * Must be provided per environment (sandbox or production).
     */
    @NotBlank(message = "QuickBooks discovery document URL is required")
    private String discoveryDocumentUrl;

    /**
     * QuickBooks API base URL (required).
     * Must be provided per environment (sandbox or production).
     */
    @NotBlank(message = "QuickBooks API base URL is required")
    private String apiBaseUrl;

    /**
     * OAuth2 scopes to request.
     */
    private String scopes = "com.intuit.quickbooks.accounting openid";

    private boolean enabled = true;

    /**
     * Resilience configuration for QuickBooks API calls.
     */
    private Resilience resilience = new Resilience();

    /**
     * Sync configuration.
     */
    private Sync sync = new Sync();

    /**
     * Webhooks configuration.
     */
    private Webhooks webhooks = new Webhooks();

    @Data
    public static class Resilience {
        private RateLimiter rateLimiter = new RateLimiter();
        private Bulkhead bulkhead = new Bulkhead();
        private Retry retry = new Retry();
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    @Data
    public static class RateLimiter {
        /**
         * Maximum requests allowed per period (QuickBooks limit: 500 per minute per realm).
         */
        private int limitPerMinute = 500;
        /**
         * Period duration for rate limit refresh (seconds).
         */
        private int limitRefreshPeriodSeconds = 60;
        /**
         * How long to wait for a permit before failing (milliseconds).
         */
        private int timeoutMillis = 500;
    }

    @Data
    public static class Bulkhead {
        /**
         * Maximum concurrent calls allowed (QuickBooks limit: 10 per realm).
         */
        private int maxConcurrentCalls = 10;
        /**
         * Maximum wait time for a slot to become available (seconds).
         */
        private int maxWaitDurationSeconds = 120; // 2 minutes
    }

    @Data
    public static class Retry {
        /**
         * Maximum number of retry attempts.
         */
        private int maxAttempts = 3;
        /**
         * Initial wait duration before retry (milliseconds).
         */
        private int waitMillis = 2000; // 2 seconds
        /**
         * Enable exponential backoff between retries.
         */
        private boolean exponentialBackoff = true;
        /**
         * Multiplier for exponential backoff (e.g., 2.0 = double wait time each retry).
         */
        private double backoffMultiplier = 2.0;
    }

    @Data
    public static class CircuitBreaker {
        /**
         * Failure rate threshold percentage (0-100) to open circuit breaker.
         */
        private int failureRateThreshold = 50; // 50%
        /**
         * Sliding window type: COUNT_BASED or TIME_BASED.
         */
        private String slidingWindowType = "COUNT_BASED";
        /**
         * Wait duration in open state before transitioning to half-open (seconds).
         */
        private int waitDurationSeconds = 60; // 60 seconds
        /**
         * Sliding window size for counting failures.
         */
        private int slidingWindowSize = 100;
        /**
         * Number of permitted calls in half-open state to test recovery.
         */
        private int permittedCallsInHalfOpen = 10;
    }

    @Data
    public static class Sync {
        private int batchSize = 30;
        private int queryLimit = 300;
        /**
         * Interval between scheduled sync runs (seconds).
         * Default: 1800 (30 minutes).
         * Override via app.integrations.quickbooks.sync.sync-interval-seconds
         * or QUICKBOOKS_SYNC_INTERVAL_SECONDS env var.
         * Production recommendation: 3600 (1 hour) for lower API usage.
         */
        private int syncIntervalSeconds = 1800; // 30 minutes
    }

    @Data
    public static class Webhooks {
        /**
         * Webhook verifier token for validating QuickBooks webhook signatures (required).
         * Used to compute HMAC SHA-256 hash for signature validation.
         */
        @NotBlank(message = "QuickBooks webhook verifier token is required")
        private String verifierToken;
    }
}
