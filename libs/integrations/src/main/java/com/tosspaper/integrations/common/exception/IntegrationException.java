package com.tosspaper.integrations.common.exception;

/**
 * Base exception for all integration-related errors.
 */
public class IntegrationException extends RuntimeException {

    public IntegrationException(String message) {
        super(message);
    }

    public IntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
