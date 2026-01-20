package com.tosspaper.aiengine.client.common.dto;

import com.tosspaper.models.domain.ExtractionStatus;
import lombok.Builder;
import lombok.Data;

/**
 * Result of starting an extraction task.
 * Contains task ID on success or error information on failure.
 */
@Data
@Builder
public class StartTaskResult {

    /**
     * Status of a start task operation.
     */
    public enum StartTaskStatus {
        SUCCESS,
        FAILED,
        UNKNOWN
    }

    /**
     * Provider-specific task ID if successful.
     */
    private final String taskId;

    /**
     * Whether the task was started successfully.
     */
    private final boolean successful;

    /**
     * Status of the start task operation.
     */
    private final StartTaskStatus status;

    /**
     * Error message if task start failed.
     */
    private final String error;

    /**
     * Exception that caused the failure.
     */
    private final Throwable throwable;

    /**
     * Check if task start was successful.
     *
     * @return true if successful, false otherwise
     */
    public boolean isSuccessful() {
        return successful;
    }

    /**
     * Check if task start failed.
     *
     * @return true if failed, false otherwise
     */
    public boolean isFailed() {
        return !successful;
    }

    /**
     * Map internal StartTaskStatus to external ExtractionStatus.
     *
     * @return corresponding ExtractionStatus
     */
    public ExtractionStatus toExtractionStatus() {
        return switch (this.status) {
            case SUCCESS -> ExtractionStatus.STARTED;
            case FAILED -> ExtractionStatus.START_TASK_FAILED;
            case UNKNOWN -> ExtractionStatus.START_TASK_UNKNOWN;
        };
    }

    /**
     * Create a successful start task result.
     *
     * @param taskId the provider task ID
     * @return successful start task result
     */
    public static StartTaskResult success(String taskId) {
        return StartTaskResult.builder()
                .taskId(taskId)
                .successful(true)
                .status(StartTaskStatus.SUCCESS)
                .build();
    }

    /**
     * Create a failed start task result.
     *
     * @param error     the error message
     * @param throwable the exception that caused the failure
     * @return failed start task result
     */
    public static StartTaskResult failure(String error, Throwable throwable) {
        return StartTaskResult.builder()
                .successful(false)
                .status(StartTaskStatus.FAILED)
                .error(error)
                .throwable(throwable)
                .build();
    }

    /**
     * Create an unknown start task result for generic exceptions.
     *
     * @param error     the error message
     * @param throwable the exception that caused the failure
     * @return unknown start task result
     */
    public static StartTaskResult unknown(String error, Throwable throwable) {
        return StartTaskResult.builder()
                .successful(false)
                .status(StartTaskStatus.UNKNOWN)
                .error(error)
                .throwable(throwable)
                .build();
    }
}
