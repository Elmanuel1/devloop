package com.tosspaper.integrations.utils;

import com.tosspaper.models.domain.integration.ProviderTracked;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility for populating provider tracking fields on entities.
 * Provider-agnostic - works with QuickBooks, Xero, Sage, etc.
 */
public class ProviderTrackingUtil {
    
    /**
     * Populate provider tracking fields.
     * Generic method for any provider.
     * 
     * @param entity Entity to populate
     * @param provider Provider name (e.g., "QUICKBOOKS", "XERO")
     * @param externalId External entity ID from provider
     * @param createdAt Creation timestamp from provider
     * @param lastUpdatedAt Last update timestamp from provider
     */
    public static void populateProviderFields(
            ProviderTracked entity,
            String provider,
            String externalId,
            OffsetDateTime createdAt,
            OffsetDateTime lastUpdatedAt
    ) {
        entity.setProvider(provider);
        entity.setExternalId(externalId);
        entity.setProviderCreatedAt(createdAt);
        entity.setProviderLastUpdatedAt(lastUpdatedAt);
    }
    
    /**
     * Add metadata entry. Creates map if it doesn't exist.
     * Null values are ignored.
     * 
     * @param entity Entity to add metadata to
     * @param key Metadata key
     * @param value Metadata value (null values are ignored)
     */
    public static void addMetadata(ProviderTracked entity, String key, Object value) {
        if (value == null) {
            return;
        }
        
        Map<String, Object> metadata = entity.getExternalMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
            entity.setExternalMetadata(metadata);
        }
        metadata.put(key, value);
    }
    
    /**
     * Get metadata value.
     * 
     * @param entity Entity to get metadata from
     * @param key Metadata key
     * @return Metadata value or null if not found
     */
    public static Object getMetadata(ProviderTracked entity, String key) {
        Map<String, Object> metadata = entity.getExternalMetadata();
        return metadata != null ? metadata.get(key) : null;
    }
    
    /**
     * Get typed metadata value.
     * 
     * @param entity Entity to get metadata from
     * @param key Metadata key
     * @param type Expected type
     * @return Typed metadata value or null if not found or wrong type
     */
    @SuppressWarnings("unchecked")
    public static <T> T getMetadata(ProviderTracked entity, String key, Class<T> type) {
        Object value = getMetadata(entity, key);
        if (value != null && type.isInstance(value)) {
            return (T) value;
        }
        return null;
    }
}
