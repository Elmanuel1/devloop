package com.tosspaper.integrations.temporal;

import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;

import java.time.OffsetDateTime;

/**
 * Simple POJO for Temporal serialization.
 * Contains only the non-sensitive fields needed by the sync workflow.
 * Tokens are NOT included to avoid persisting them in Temporal history.
 * Activities that need tokens should re-fetch via connectionService.
 */
public class SyncConnectionData {

    private String id;
    private Long companyId;
    private IntegrationProvider provider;
    private OffsetDateTime expiresAt;
    private String realmId;
    private OffsetDateTime lastSyncAt;
    private OffsetDateTime syncFrom;

    public SyncConnectionData() {
    }

    public SyncConnectionData(String id, Long companyId, IntegrationProvider provider,
                              OffsetDateTime expiresAt, String realmId,
                              OffsetDateTime lastSyncAt, OffsetDateTime syncFrom) {
        this.id = id;
        this.companyId = companyId;
        this.provider = provider;
        this.expiresAt = expiresAt;
        this.realmId = realmId;
        this.lastSyncAt = lastSyncAt;
        this.syncFrom = syncFrom;
    }

    /**
     * Create from domain model (excludes tokens for security).
     */
    public static SyncConnectionData from(IntegrationConnection connection) {
        return new SyncConnectionData(
                connection.getId(),
                connection.getCompanyId(),
                connection.getProvider(),
                connection.getExpiresAt(),
                connection.getRealmId(),
                connection.getLastSyncAt(),
                connection.getSyncFrom()
        );
    }

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public IntegrationProvider getProvider() {
        return provider;
    }

    public void setProvider(IntegrationProvider provider) {
        this.provider = provider;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getRealmId() {
        return realmId;
    }

    public void setRealmId(String realmId) {
        this.realmId = realmId;
    }

    public OffsetDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(OffsetDateTime lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public OffsetDateTime getSyncFrom() {
        return syncFrom;
    }

    public void setSyncFrom(OffsetDateTime syncFrom) {
        this.syncFrom = syncFrom;
    }
}
