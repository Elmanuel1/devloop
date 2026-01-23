package com.tosspaper.integrations.common.exception;

import lombok.Getter;

/**
 * Exception thrown when a sync operation fails.
 */
@Getter
public class IntegrationSyncException extends IntegrationException {

    private final boolean retryable;

    public IntegrationSyncException(String message) {
        this(message, true);
    }

    public IntegrationSyncException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public IntegrationSyncException(String message, Throwable cause) {
        this(message, cause, true);
    }

    public IntegrationSyncException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

}
