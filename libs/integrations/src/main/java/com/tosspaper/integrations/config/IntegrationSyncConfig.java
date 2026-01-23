package com.tosspaper.integrations.config;

import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Generic sync configuration service for all integration providers.
 * Calculates query limits based on batch size and sync interval.
 */
@Component
@RequiredArgsConstructor
public class IntegrationSyncConfig {

    private final QuickBooksProperties quickBooksProperties;

    /**
     * Sync configuration values for a provider.
     * Serializable record that can be passed to Temporal workflows.
     */
    public record SyncSettings(int batchSize, int queryLimit) {}

    /**
     * Get sync settings for a provider.
     */
    public SyncSettings getSyncSettings(IntegrationProvider provider) {
        return switch (provider) {
            case QUICKBOOKS -> new SyncSettings(
                    quickBooksProperties.getSync().getBatchSize(),
                    getQueryLimit(provider)
            );
            case XERO, SAGE -> new SyncSettings(30, 300); // Defaults for other providers
        };
    }

    /**
     * Calculate query limit for a provider based on batch size and concurrent processing capacity.
     * Formula: batchSize * maxConcurrentCalls
     */
    public int getQueryLimit(IntegrationProvider provider) {
        return switch (provider) {
            case QUICKBOOKS -> {
                int batchSize = quickBooksProperties.getSync().getBatchSize();
                int maxConcurrentCalls = quickBooksProperties.getResilience().getBulkhead().getMaxConcurrentCalls();
                yield batchSize * maxConcurrentCalls;
            }
            case XERO, SAGE -> 300; // Default for other providers
        };
    }
}



