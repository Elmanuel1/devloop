package com.tosspaper.models.service;

import com.tosspaper.models.domain.PurchaseOrder;

import java.util.List;

/**
 * Service for purchase order sync operations.
 * Used for syncing purchase orders to/from external providers.
 */
public interface PurchaseOrderSyncService {

    /**
     * Upsert purchase orders from provider.
     * Creates new POs or updates existing ones based on company_id + provider +
     * external_id.
     * Skips upsert if local entity has version > pushed_version (pending local
     * changes).
     *
     * @param companyId      company ID
     * @param purchaseOrders list of purchase orders to upsert
     */
    void upsertFromProvider(Long companyId, List<PurchaseOrder> purchaseOrders);

    /**
     * Delete purchase orders by provider and external IDs.
     *
     * @param companyId   company ID
     * @param provider    the integration provider name (e.g., "QUICKBOOKS")
     * @param externalIds list of external IDs to delete
     * @return number of records deleted
     */
    int deleteByProviderAndExternalIds(Long companyId, String provider, List<String> externalIds);

    /**
     * Update sync status after successful push to provider.
     * Sets externalId (for creates), providerVersion (sync token), and
     * providerLastUpdatedAt.
     *
     * @param poId                  internal purchase order ID
     * @param externalId            external ID from provider
     * @param providerVersion       sync token from provider
     * @param providerLastUpdatedAt last updated timestamp from provider (QB's
     *                              MetaData.LastUpdatedTime)
     */
    void updateSyncStatus(String poId, String externalId, String providerVersion,
            java.time.OffsetDateTime providerLastUpdatedAt);

    /**
     * Find purchase orders that need to be pushed to external provider.
     * Returns POs where last_sync_at IS NULL (never synced or failed sync).
     * Excludes permanently failed POs and those that exceeded max retries.
     *
     * @param companyId  company ID
     * @param limit      maximum number of POs to fetch
     * @param maxRetries maximum number of retry attempts allowed
     * @return list of purchase orders needing push
     */
    List<PurchaseOrder> findNeedingPush(Long companyId, int limit, int maxRetries);

    /**
     * Find purchase order by provider and external ID.
     *
     * @param companyId  company ID
     * @param provider   the integration provider name (e.g., "quickbooks")
     * @param externalId external ID from provider
     * @return purchase order if found, null otherwise
     */
    PurchaseOrder findByProviderAndExternalId(Long companyId, String provider, String externalId);

    /**
     * Find purchase orders by company ID and display IDs (batch lookup).
     *
     * @param companyId  company ID
     * @param displayIds list of purchase order display IDs
     * @return list of purchase orders found
     */
    List<PurchaseOrder> findByCompanyIdAndDisplayIds(Long companyId, List<String> displayIds);

    /**
     * Find purchase order by ID.
     *
     * @param poId purchase order ID
     * @return purchase order if found, null otherwise
     */
    PurchaseOrder findById(String poId);

    /**
     * Increment retry count for a purchase order after a failed push attempt.
     *
     * @param poId         purchase order ID
     * @param errorMessage error message from the failed push attempt
     */
    void incrementRetryCount(String poId, String errorMessage);

    /**
     * Mark a purchase order as permanently failed (no more retries).
     *
     * @param poId         purchase order ID
     * @param errorMessage error message explaining why it's permanently failed
     */
    void markAsPermanentlyFailed(String poId, String errorMessage);

    /**
     * Reset retry tracking for a purchase order.
     *
     * @param poId purchase order ID
     */
    void resetRetryTracking(String poId);

}
