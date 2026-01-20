package com.tosspaper.models.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Result of pushing an entity to an external system.
 * Contains the external ID assigned by the provider and sync timestamp.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushResult {

    /**
     * The internal document ID (e.g. Invoice ID).
     */
    private String documentId;

    /**
     * The external ID assigned by the provider (for newly created entities).
     * Null if entity already existed in provider.
     */
    private String externalId;

    /**
     * Timestamp when the entity was successfully pushed to the external provider.
     * Used to track last_sync_at for invoices and other entities.
     */
    private OffsetDateTime syncedAt;

    public static PushResult of(String documentId, String externalId, OffsetDateTime syncedAt) {
        return new PushResult(documentId, externalId, syncedAt);
    }

    public static PushResult of(String externalId, OffsetDateTime syncedAt) {
        return new PushResult(null, externalId, syncedAt);
    }

    public static PushResult of(String externalId) {
        return new PushResult(null, externalId, null);
    }
}
