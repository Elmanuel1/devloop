package com.tosspaper.models.domain;

/**
 * Enum representing the sync status of a document approval.
 * Used at service level; database stores lowercase string values.
 */
public enum DocumentSyncStatus {
    PENDING("pending"),
    SYNCED("synced"),
    FAILED("failed");

    private final String value;

    DocumentSyncStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DocumentSyncStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (DocumentSyncStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown sync status: " + value);
    }
}
