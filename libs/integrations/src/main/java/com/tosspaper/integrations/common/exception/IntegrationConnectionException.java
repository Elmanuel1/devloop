package com.tosspaper.integrations.common.exception;

/**
 * Exception thrown when a connection to an external system cannot be established.
 */
public class IntegrationConnectionException extends IntegrationException {

    public IntegrationConnectionException(String message) {
        super(message);
    }

    public IntegrationConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
