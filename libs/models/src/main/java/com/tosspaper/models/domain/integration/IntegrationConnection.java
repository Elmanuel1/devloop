package com.tosspaper.models.domain.integration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Domain model representing an OAuth connection to an external financial system.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntegrationConnection {

    private String id;
    private Long companyId;
    private IntegrationProvider provider;
    private IntegrationConnectionStatus status;

    // OAuth tokens
    private String accessToken;
    private String refreshToken;
    private OffsetDateTime expiresAt;
    private OffsetDateTime refreshTokenExpiresAt;

    // Provider-specific identifiers
    private String realmId;              // QuickBooks realm/company ID
    private String externalCompanyId;    // Generic external company ID
    private String externalCompanyName;  // Display name from external system
    private com.tosspaper.models.domain.Currency defaultCurrency;  // Default currency from provider (e.g., QBO HomeCurrency)
    private Boolean multicurrencyEnabled; // Whether multicurrency is enabled in the provider's company
    private IntegrationCategory category; // Category: FINANCIAL, ACCOUNTING, FILES, etc. Only one connection per category can be enabled.

    // Metadata
    private String scopes;
    private OffsetDateTime lastSyncAt;
    private OffsetDateTime lastSyncStartedAt;
    private OffsetDateTime lastSyncCompletedAt;
    
    // Cursor for push sync
    private String lastPushCursor;
    private OffsetDateTime lastPushCursorAt;
    private OffsetDateTime syncFrom;
    
    private String errorMessage;
    private Map<String, Object> metadata;

    // Timestamps
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /**
     * Check if the access token is valid (not expired).
     *
     * @return true if token is valid (expiresAt is in the future)
     */
    @JsonIgnore
    public boolean isTokenValid() {
        if (expiresAt == null) {
            return false;
        }
        return OffsetDateTime.now().isBefore(expiresAt);
    }

    /**
     * Check if the connection has sync enabled.
     *
     * @return true if status is ENABLED
     */
    @JsonIgnore
    public boolean isEnabled() {
        return status == IntegrationConnectionStatus.ENABLED;
    }
}
