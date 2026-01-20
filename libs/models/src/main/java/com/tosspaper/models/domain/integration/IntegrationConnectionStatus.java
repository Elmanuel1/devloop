package com.tosspaper.models.domain.integration;

import lombok.Getter;

/**
 * Status of an integration connection.
 */
@Getter
public enum IntegrationConnectionStatus {
    DISABLED("disabled"),
    ENABLED("enabled"),
    EXPIRED("expired"),
    REVOKED("revoked");

    private final String value;

    IntegrationConnectionStatus(String value) {
        this.value = value;
    }

    public static IntegrationConnectionStatus fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (IntegrationConnectionStatus status : IntegrationConnectionStatus.values()) {
            if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown connection status: " + value);
    }
}
