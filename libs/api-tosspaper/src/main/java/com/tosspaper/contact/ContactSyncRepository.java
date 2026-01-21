package com.tosspaper.contact;

import com.tosspaper.models.common.SyncStatusUpdate;
import com.tosspaper.models.domain.Party;

import java.util.List;

/**
 * Repository for contact/vendor sync operations.
 * Used for syncing vendors from external providers.
 */
public interface ContactSyncRepository {

    /**
     * Upsert contacts from provider.
     * Creates new contacts or updates existing ones based on company_id + provider + external_id.
     * Skips upsert if local entity has version > pushed_version (pending local changes).
     * Provider is read from each Party object's provider field.
     *
     * @param companyId company ID
     * @param contacts list of contacts to upsert (each Party should have provider field set)
     */
    void upsertFromProvider(Long companyId, List<Party> contacts);

    /**
     * Update sync status after successful push to provider.
     * Sets provider, externalId (for creates), providerVersion (sync token), and providerLastUpdatedAt.
     *
     * @param contactId internal contact ID
     * @param provider provider name (e.g., "quickbooks")
     * @param externalId external ID from provider
     * @param providerVersion sync token from provider
     * @param providerLastUpdatedAt last updated timestamp from provider (QB's MetaData.LastUpdatedTime)
     */
    void updateSyncStatus(String contactId, String provider, String externalId, String providerVersion, java.time.OffsetDateTime providerLastUpdatedAt);

    /**
     * Batch update sync status for multiple contacts.
     *
     * @param updates list of sync status updates
     */
    void batchUpdateSyncStatus(List<SyncStatusUpdate> updates);

    /**
     * Find a contact by its internal ID.
     * Returns full Party with provider sync fields (externalId, providerVersion, etc.).
     *
     * @param id internal contact ID
     * @return Party with sync info, or null if not found
     */
    Party findById(String id);

    /**
     * Find contacts by IDs (batch lookup).
     *
     * @param ids list of contact IDs
     * @return list of contacts found
     */
    List<Party> findByIds(List<String> ids);

    /**
     * Find contacts that need to be pushed to external provider, filtered by tags.
     * Returns contacts where last_sync_at IS NULL (never synced or failed sync)
     * and tag matches one of the specified tags.
     * Excludes permanently failed contacts and those that exceeded max retries.
     *
     * @param companyId company ID
     * @param limit maximum number of contacts to fetch
     * @param tags list of tags to filter by (e.g., ["vendor", "supplier"] or ["ship_to"])
     *             If null or empty, returns all contacts needing push regardless of tag
     * @param maxRetries maximum number of retry attempts allowed
     * @return list of contacts needing push
     */
    List<Party> findNeedingPush(Long companyId, int limit, List<String> tags, int maxRetries);

    /**
     * Find contacts by provider and external IDs (batch lookup).
     * Used to enrich purchase orders with full contact details from database.
     *
     * @param companyId company ID
     * @param provider provider name (e.g., "quickbooks")
     * @param externalIds list of external IDs to look up
     * @return list of contacts found (may be fewer than requested if some don't exist)
     */
    List<Party> findByProviderAndExternalIds(Long companyId, String provider, List<String> externalIds);

    /**
     * Increment retry count for a contact after a failed push attempt.
     *
     * @param contactId internal contact ID
     * @param errorMessage the error message from the failed attempt
     */
    void incrementRetryCount(String contactId, String errorMessage);

    /**
     * Mark a contact as permanently failed (no more retries).
     * Used when max retries exceeded or non-retryable error encountered.
     *
     * @param contactId internal contact ID
     * @param errorMessage the final error message
     */
    void markAsPermanentlyFailed(String contactId, String errorMessage);

    /**
     * Reset retry tracking for a contact.
     * Used by admin API to manually retry a contact that was marked as failed.
     *
     * @param contactId internal contact ID
     */
    void resetRetryTracking(String contactId);
}
