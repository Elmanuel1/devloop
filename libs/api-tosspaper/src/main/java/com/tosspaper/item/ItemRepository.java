package com.tosspaper.item;

import com.tosspaper.common.DuplicateException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.domain.integration.Item;

import java.util.List;
import java.util.Map;

/**
 * Repository for Items operations.
 * Used for syncing items (products/services) from external providers.
 * Items are connection-specific and require a company_id.
 */
public interface ItemRepository {

    /**
     * Upsert items from the provider.
     * Creates new items or updates existing ones based on connection_id + external_id.
     *
     * @param companyId company ID (required)
     * @param connectionId connection ID
     * @param items list of items to upsert
     */
    void upsert(Long companyId, String connectionId, List<Item> items);

    /**
     * Upsert items from provider sync.
     * Creates new items or updates existing ones based on connection_id + external_id.
     *
     * @param companyId company ID
     * @param connectionId connection ID
     * @param items list of items to upsert
     */
    void upsertFromProvider(Long companyId, String connectionId, List<Item> items);

    /**
     * Find all items for a company.
     *
     * @param companyId company ID
     * @return list of items
     */
    List<Item> findByCompanyId(Long companyId);
    /**
     * Find item by UUID.
     *
     * @param id item UUID
     * @return item if found
     * @throws NotFoundException if item not found
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
     * Create a new item.
     *
     * @param companyId company ID
     * @param item item to create
     * @return created item with generated ID
     * @throws DuplicateException if item with same name exists for company
     */
    Item create(Long companyId, Item item);

    /**
     * Update an existing item.
     *
     * @param item item with updated fields
     * @return updated item
     */
    Item update(Item item);

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
     * Sets provider, externalId (for creations), providerVersion (sync token), and providerLastUpdatedAt.
     *
     * @param itemId item ID
     * @param provider provider name (e.g., "quickbooks")
     * @param externalId external ID from provider
     * @param providerVersion sync token from provider
     * @param providerLastUpdatedAt last updated timestamp from provider (QB's MetaData.LastUpdatedTime)
     */
    void updateSyncStatus(String itemId, String provider, String externalId, String providerVersion, java.time.OffsetDateTime providerLastUpdatedAt);

    /**
     * Batch update sync status for multiple items.
     *
     * @param updates list of sync status updates
     */
    void batchUpdateSyncStatus(List<com.tosspaper.models.common.SyncStatusUpdate> updates);

    /**
     * Find items that need to be pushed to an external provider.
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
     * Clear sync status to mark the item for retry.
     * Sets last_sync_at = NULL.
     *
     * @param itemId item ID
     */
    void clearSyncStatus(String itemId);

    /**
     * Increment retry count for an item after a failed push attempt.
     *
     * @param itemId item ID
     * @param errorMessage the error message from the failed attempt
     */
    void incrementRetryCount(String itemId, String errorMessage);

    /**
     * Mark an item as permanently failed (no more retries).
     * Used when max retries exceeded or non-retryable error encountered.
     *
     * @param itemId item ID
     * @param errorMessage the final error message
     */
    void markAsPermanentlyFailed(String itemId, String errorMessage);

    /**
     * Reset retry tracking for an item.
     * Used by admin API to manually retry an item that was marked as failed.
     *
     * @param itemId item ID
     */
    void resetRetryTracking(String itemId);
}
