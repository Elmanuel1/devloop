package com.tosspaper.models.domain.integration;

import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.AccessLevel;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Base class for entities that can be synced with external providers.
 * Provides shared provider tracking fields.
 */
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public abstract class ProviderTracked {
    
    // Field name constants (for JOOQ, queries, logging)
    public static final String FIELD_PROVIDER = "provider";
    public static final String FIELD_EXTERNAL_ID = "external_id";
    public static final String FIELD_EXTERNAL_METADATA = "external_metadata";
    public static final String FIELD_PROVIDER_CREATED_AT = "provider_created_at";
    public static final String FIELD_PROVIDER_LAST_UPDATED_AT = "provider_last_updated_at";
    public static final String FIELD_PROVIDER_VERSION = "provider_version";
    public static final String FIELD_PUSH_RETRY_COUNT = "push_retry_count";
    public static final String FIELD_PUSH_RETRY_LAST_ATTEMPT_AT = "push_retry_last_attempt_at";
    public static final String FIELD_PUSH_PERMANENTLY_FAILED = "push_permanently_failed";
    public static final String FIELD_PUSH_FAILURE_REASON = "push_failure_reason";
    
    // Metadata key constants
    public static final String METADATA_KEY_TERMS_REF_ID = "terms_ref_id";
    public static final String METADATA_KEY_TERMS_REF_NAME = "terms_ref_name";
    public static final String METADATA_KEY_BALANCE = "balance";
    public static final String METADATA_KEY_ACCOUNT_NUMBER = "account_number";
    public static final String METADATA_KEY_VENDOR_REF_ID = "vendor_ref_id";
    public static final String METADATA_KEY_STATUS = "status";
    public static final String METADATA_KEY_MEMO = "memo";
    
    // Shared provider tracking fields
    String provider;
    String externalId;
    Map<String, Object> externalMetadata;
    /**
     * Provider-specific version token for optimistic concurrency control.
     * Examples:
     * - QuickBooks: SyncToken
     * - Other providers: their version/etag token
     */
    String providerVersion;
    
    // Optional: Only for entities that PULL from provider
    OffsetDateTime providerCreatedAt;
    OffsetDateTime providerLastUpdatedAt;

    // NOTE: We no longer use local version/pushedVersion counters for push tracking.

    // Push retry tracking fields
    Integer pushRetryCount = 0;
    OffsetDateTime pushRetryLastAttemptAt;
    Boolean pushPermanentlyFailed = false;
    String pushFailureReason;
    
    /**
     * Check if this entity was synced from an external provider.
     */
    public boolean isSynced() {
        return provider != null;
    }
    
    /**
     * Check if this entity is a local record (not synced).
     */
    public boolean isLocal() {
        return provider == null;
    }
    
    /**
     * Check if this entity was synced from a specific provider.
     */
    public boolean isSyncedFrom(String provider) {
        return provider != null && provider.equals(this.provider);
    }

    /**
     * Check if this entity can be updated in the provider (has an externalId).
     * Returns true for UPDATE operations, false for CREATE operations.
     */
    public boolean isUpdatable() {
        return externalId != null;
    }

    /**
     * Check if this entity can be retried for push operations.
     * Returns false if permanently failed or exceeded max retries.
     *
     * @param maxRetries the maximum number of retry attempts allowed
     * @return true if the entity can be retried, false otherwise
     */
    public boolean canRetry(int maxRetries) {
        return !Boolean.TRUE.equals(pushPermanentlyFailed) &&
               (pushRetryCount == null || pushRetryCount < maxRetries);
    }

    /**
     * Copy all provider tracking fields from source to this object.
     * Use after MapStruct mapping to preserve provider fields that MapStruct doesn't auto-map.
     *
     * This utility method helps avoid bugs where MapStruct doesn't map inherited fields,
     * causing provider fields to be null and triggering constraint violations.
     *
     * @param source the source ProviderTracked object to copy from
     */
    public void copyProviderFieldsFrom(ProviderTracked source) {
        if (source == null) {
            return;
        }
        this.provider = source.getProvider();
        this.externalId = source.getExternalId();
        this.externalMetadata = source.getExternalMetadata();
        this.providerVersion = source.getProviderVersion();
        this.providerCreatedAt = source.getProviderCreatedAt();
        this.providerLastUpdatedAt = source.getProviderLastUpdatedAt();
        this.pushRetryCount = source.getPushRetryCount();
        this.pushRetryLastAttemptAt = source.getPushRetryLastAttemptAt();
        this.pushPermanentlyFailed = source.getPushPermanentlyFailed();
        this.pushFailureReason = source.getPushFailureReason();
    }
}
