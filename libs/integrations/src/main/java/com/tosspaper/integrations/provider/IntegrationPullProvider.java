package com.tosspaper.integrations.provider;

import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;

import java.util.List;

/**
 * Generic interface for pull providers (QuickBooks, Xero, Sage, etc.).
 * Handles fetching data from external systems.
 * Similar structure to IntegrationPushProvider for consistency.
 */
public interface IntegrationPullProvider<T> {

    /**
     * Check if this provider is enabled.
     */
    boolean isEnabled();

    /**
     * Get the provider enum.
     */
    IntegrationProvider getProviderId();

    /**
     * Get the entity type this provider handles.
     */
    IntegrationEntityType getEntityType();


    /**
     * Pull a batch of entities from the external system.
     * Returns a map of external ID to entity for accurate tracking.
     * Uses connection.getLastSyncAt() to determine which entities to fetch.
     *
     * @param connection the integration connection to use
     * @return map of external ID to entity
     */
    List<T> pullBatch(IntegrationConnection connection);

    /**
     * Pull a single entity from the external system by its external ID.
     * 
     * @param externalId the external ID of the entity to fetch
     * @param connection the integration connection to use
     * @return the entity if found, null otherwise
     */
    T getById(String externalId, IntegrationConnection connection);
}
