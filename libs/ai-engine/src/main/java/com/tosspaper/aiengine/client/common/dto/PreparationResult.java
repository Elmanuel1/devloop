package com.tosspaper.aiengine.client.common.dto;

import com.tosspaper.models.domain.ExtractionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Result of file preparation for AI processing.
 * Contains preparation status, timing information, and any errors.
 */
@Data
@Builder
public class PreparationResult {

    /**
     * Status of the preparation process.
     */
    private final ExtractionStatus status;

    /**
     * Provider-specific preparation ID.
     */
    private final String preparationId;

    /**
     * When preparation started.
     */
    private final Instant startedAt;

    /**
     * When preparation completed (successfully or with failure).
     */
    private final Instant completedAt;

    /**
     * Error message if preparation failed.
     */
    private final String error;

    /**
     * Exception that caused the failure.
     */
    private final Throwable throwable;

    /**
     * Check if preparation was successful.
     *
     * @return true if ready, false otherwise
     */
    public boolean isSuccessful() {
        return status == ExtractionStatus.PREPARE_SUCCEEDED;
    }

    /**
     * Check if preparation failed.
     *
     * @return true if failed, false otherwise
     */
    public boolean isFailed() {
        return status == ExtractionStatus.PREPARE_FAILED || status == ExtractionStatus.PREPARE_UNKNOWN;
    }

    /**
     * Create a successful preparation result.
     *
     * @param preparationId the provider preparation ID
     * @param startedAt     when preparation started
     * @param completedAt   when preparation completed
     * @return successful preparation result
     */
    public static PreparationResult success(String preparationId, Instant startedAt, Instant completedAt) {
        return PreparationResult.builder()
                .status(ExtractionStatus.PREPARE_SUCCEEDED)
                .preparationId(preparationId)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();
    }

    /**
     * Create a failed preparation result.
     *
     * @param error      the error message
     * @param throwable  the exception that caused the failure
     * @param startedAt  when preparation started
     * @param completedAt when preparation failed
     * @return failed preparation result
     */
    public static PreparationResult failure(String error, Throwable throwable, Instant startedAt, Instant completedAt) {
        return PreparationResult.builder()
                .status(ExtractionStatus.PREPARE_FAILED)
                .error(error)
                .throwable(throwable)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();
    }

    /**
     * Create an unknown preparation result.
     *
     * @param error      the error message
     * @param throwable  the exception that caused the unknown status
     * @param startedAt  when preparation started
     * @param completedAt when preparation completed with unknown status
     * @return unknown preparation result
     */
    public static PreparationResult unknown(String error, Throwable throwable, Instant startedAt, Instant completedAt) {
        return PreparationResult.builder()
                .status(ExtractionStatus.PREPARE_UNKNOWN)
                .error(error)
                .throwable(throwable)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .build();
    }
}
