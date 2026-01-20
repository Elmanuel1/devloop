package com.tosspaper.models.common;

import java.time.OffsetDateTime;

/**
 * DTO for batch sync status updates.
 * Used after successfully pushing entities to external providers.
 * Shared across all entity types (contacts, items, purchase orders, etc.).
 */
public record SyncStatusUpdate(
    String id,
    String provider,
    String externalId,
    String providerVersion,
    OffsetDateTime providerLastUpdatedAt
) {}
