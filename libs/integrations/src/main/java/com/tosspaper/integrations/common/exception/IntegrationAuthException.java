package com.tosspaper.integrations.common.exception;

import lombok.Getter;

/**
 * Exception thrown when integration authentication fails.
 * This includes OAuth token issues, expired tokens, or revoked access.
 */
@Getter
public class IntegrationAuthException extends IntegrationException {

    private final String errorCode;

    public IntegrationAuthException(String message) {
        super(message);
        this.errorCode = null;
    }

    public IntegrationAuthException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public IntegrationAuthException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }

    public IntegrationAuthException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
