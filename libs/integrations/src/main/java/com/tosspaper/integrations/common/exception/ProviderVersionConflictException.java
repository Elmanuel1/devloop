package com.tosspaper.integrations.common.exception;

/**
 * Thrown when an external provider rejects an update due to a provider-specific version mismatch.
 * Examples:
 * - QuickBooks: SyncToken mismatch
 * - Other providers: etag/version mismatch
 *
 * Intended to be caught at the API/service layer to trigger a "pull latest then fail" flow (provider wins).
 */
public class ProviderVersionConflictException extends IntegrationException {

    public ProviderVersionConflictException(String message) {
        super(message);
    }

    public ProviderVersionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}


