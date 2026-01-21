package com.tosspaper.purchaseorder.model;

import java.time.OffsetDateTime;

/**
 * Represents an immutable entry in the change log for a purchase order.
 *
 * @param timestamp The exact date and time when the change was recorded.
 * @param authorId The identifier of the user who performed the action.
 * @param action The type of action performed, e.g., "status_change", "update_fields".
 * @param from The original state or value before the change. For a status change, this would be the previous status.
 * @param to The new state or value after the change. For a status change, this would be the new status.
 * @param note Any additional note or comment related to the change.
 */
public record ChangeLogEntry(
        OffsetDateTime timestamp,
        String authorId,
        String action,
        String from,
        String to,
        String note
) {
} 