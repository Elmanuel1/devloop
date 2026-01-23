package com.tosspaper.integrations.quickbooks.client;

import java.time.OffsetDateTime;

/**
 * Represents a CDC (Change Data Capture) result from QuickBooks.
 * Contains minimal info needed to process changes.
 */
public record CDCResult(
        String id,
        boolean deleted,
        OffsetDateTime lastUpdatedTime
) {
}
