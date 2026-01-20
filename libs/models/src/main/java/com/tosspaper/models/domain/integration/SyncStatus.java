package com.tosspaper.models.domain.integration;

import lombok.Getter;

/**
 * Status of a document sync operation.
 */
@Getter
public enum SyncStatus {
    PENDING("pending"),
    SYNCING("syncing"),
    COMPLETED("completed"),
    FAILED("failed"),
    MANUAL_REVIEW("manual_review");

    private final String value;

    SyncStatus(String value) {
        this.value = value;
    }

    public static SyncStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SyncStatus status : SyncStatus.values()) {
            if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown sync status: " + value);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == MANUAL_REVIEW;
    }

    public boolean canRetry() {
        return this == FAILED || this == PENDING;
    }
}
