package com.tosspaper.integrations.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a sync operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncResult {

    private boolean success;
    private String externalId;
    private String externalDocNumber;
    private String providerVersion;      // Updated SyncToken from QB response
    private java.time.OffsetDateTime providerLastUpdatedAt;  // QB's MetaData.LastUpdatedTime from response
    private String errorMessage;
    private boolean retryable;
    private boolean conflictDetected;    // Flag for sync token conflict notification

    public static SyncResult success(String externalId, String externalDocNumber) {
        return SyncResult.builder()
                .success(true)
                .externalId(externalId)
                .externalDocNumber(externalDocNumber)
                .retryable(false)
                .build();
    }

    public static SyncResult success(String externalId, String externalDocNumber, String providerVersion, java.time.OffsetDateTime providerLastUpdatedAt) {
        return SyncResult.builder()
                .success(true)
                .externalId(externalId)
                .externalDocNumber(externalDocNumber)
                .providerVersion(providerVersion)
                .providerLastUpdatedAt(providerLastUpdatedAt)
                .retryable(false)
                .build();
    }

    public static SyncResult failure(String errorMessage, boolean retryable) {
        return SyncResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .retryable(retryable)
                .build();
    }

    public static SyncResult conflict(String errorMessage) {
        return SyncResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .retryable(false)
                .conflictDetected(true)
                .build();
    }
}
