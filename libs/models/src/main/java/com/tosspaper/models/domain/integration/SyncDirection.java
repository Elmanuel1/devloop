package com.tosspaper.models.domain.integration;

import lombok.Getter;

/**
 * Direction of a sync operation.
 */
@Getter
public enum SyncDirection {
    OUTBOUND("outbound"),   // Local system -> External system (e.g., Invoice -> QBO Bill)
    INBOUND("inbound");     // External system -> Local system (e.g., QBO PO -> local PO)

    private final String value;

    SyncDirection(String value) {
        this.value = value;
    }

    public static SyncDirection fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SyncDirection direction : SyncDirection.values()) {
            if (direction.value.equalsIgnoreCase(value) || direction.name().equalsIgnoreCase(value)) {
                return direction;
            }
        }
        throw new IllegalArgumentException("Unknown sync direction: " + value);
    }
}
