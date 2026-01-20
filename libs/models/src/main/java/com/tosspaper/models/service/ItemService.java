package com.tosspaper.models.service;

import com.tosspaper.models.common.SyncStatusUpdate;
import com.tosspaper.models.domain.integration.Item;

import java.util.List;
import java.util.Map;

/**
 * Service for Item operations.
 */
public interface ItemService {

    /**
     * Find item by ID.
     *
     * @param id item ID
     * @return item if found, null otherwise
     */
    Item findById(String id);

    /**
     * Find items by IDs (batch lookup).
     *
     * @param ids list of item IDs
     * @return list of items found
     */
    List<Item> findByIds(List<String> ids);

    /**
     * Upsert items from provider sync.
     *
     * @param companyId company ID
     * @param connectionId integration connection ID
     * @param items items to upsert
     */
    void upsertFromProvider(Long companyId, String connectionId, List<Item> items);

    /**
     * Batch lookup items by external IDs and connection ID.
     * Returns a map of external_id -> item.id for efficient resolution.
     *
     * @param connectionId connection ID
     * @param externalIds list of external IDs to lookup
     * @return map of external_id to internal item id
     */
    Map<String, String> findIdsByExternalIdsAndConnection(String connectionId, List<String> externalIds);

    /**
     * Update sync status after successful push to provider.
     * Sets provider, externalId (for creates), providerVersion (sync token), and providerLastUpdatedAt.
     *
     * @param itemId item ID
     * @param provider provider name (e.g., "quickbooks")
     * @param externalId external ID from provider
     * @param providerVersion sync token from provider
     * @param providerLastUpdatedAt last updated timestamp from provider (QB's MetaData.LastUpdatedTime)
     */
    void updateSyncStatus(String itemId, String provider, String externalId, String providerVersion, java.time.OffsetDateTime providerLastUpdatedAt);

    /**
     * Batch update sync status for multiple items after successful push to provider.
     * More efficient than calling updateSyncStatus multiple times.
     *
     * @param updates list of sync status updates
     */
    void batchUpdateSyncStatus(java.util.List<SyncStatusUpdate> updates);

    /**
     * Find items that need to be pushed to external provider.
     * Returns items where last_sync_at IS NULL (never synced or failed sync).
     * Excludes permanently failed items and those that exceeded max retries.
     *
     * @param companyId company ID
     * @param connectionId connection ID
     * @param limit maximum number of items to fetch
     * @param maxRetries maximum number of retry attempts allowed
     * @return list of items needing push
     */
    List<Item> findNeedingPush(Long companyId, String connectionId, int limit, int maxRetries);

    /**
     * Clear sync status to mark item for retry.
     */
    void clearSyncStatus(String itemId);

    /**
     * Increment retry count for an item after a failed push attempt.
     */
    void incrementRetryCount(String itemId, String errorMessage);

    /**
     * Mark an item as permanently failed (no more retries).
     */
    void markAsPermanentlyFailed(String itemId, String errorMessage);

    /**
     * Reset retry tracking for an item.
     */
    void resetRetryTracking(String itemId);
}
