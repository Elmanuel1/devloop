package com.tosspaper.integrations.common;

import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Result object for dependency push operations.
 * Provides clear success/failure status with error messages.
 */
@Value
@Builder
public class DependencyPushResult {
    boolean success;
    String message;
    @Builder.Default
    List<String> errors = new ArrayList<>();

    /**
     * Creates a success result.
     */
    public static DependencyPushResult success() {
        return DependencyPushResult.builder()
            .success(true)
            .message("All dependencies ready")
            .build();
    }

    /**
     * Creates a success result with a custom message.
     */
    public static DependencyPushResult success(String message) {
        return DependencyPushResult.builder()
            .success(true)
            .message(message)
            .build();
    }

    /**
     * Creates a failure result with a single error message.
     */
    public static DependencyPushResult failure(String message) {
        return DependencyPushResult.builder()
            .success(false)
            .message(message)
            .errors(List.of(message))
            .build();
    }

    /**
     * Creates a failure result with multiple error messages.
     */
    public static DependencyPushResult failure(String message, List<String> errors) {
        return DependencyPushResult.builder()
            .success(false)
            .message(message)
            .errors(errors)
            .build();
    }
}
