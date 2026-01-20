package com.tosspaper.aiengine.client.common.dto;

import com.tosspaper.aiengine.client.common.exception.TaskNotFoundException;
import com.tosspaper.models.domain.ExtractionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Result of getting an extract task by ID.
 * Contains full task details including output, citations, and metrics.
 */
@Data
@Builder
public class ExtractTaskResult {

    /**
     * Task ID.
     */
    private final String taskId;

    /**
     * File ID (for file search results).
     */
    private final String fileId;

    /**
     * Task status using internal ExtractionStatus enum.
     */
    private final ExtractionStatus status;

    /**
     * Whether the task is completed (terminal state).
     */
    private final boolean completed;

    /**
     * Task message describing status or errors.
     */
    private final String message;

    /**
     * When the task was created.
     */
    private final OffsetDateTime createdAt;

    /**
     * When the task was started.
     */
    private final OffsetDateTime startedAt;

    /**
     * Document type detected by the processing service.
     * Values: "po", "invoice", "delivery_slip", "unknown"
     */
    private final String type;

    /**
     * When the task was finished.
     */
    private final OffsetDateTime finishedAt;

    /**
     * File information.
     */
    private final FileInfo fileInfo;

    /**
     * Extraction output results.
     */
    private final Map<String, Object> output;

    /**
     * Extraction citations.
     */
    private final Map<String, Object> citations;

    /**
     * Extraction metrics.
     */
    private final Map<String, Object> metrics;

    /**
     * Whether the task was found.
     */
    private final boolean found;

    /**
     * Error message if task was not found or request failed.
     */
    private final String error;

    /**
     * Exception that caused the failure.
     */
    private final Throwable throwable;

    /**
     * Raw response from the provider as JSON string (for full data preservation).
     */
    private final String rawResponse;

    /**
     * Check if task was found successfully.
     *
     * @return true if found, false otherwise
     */
    public boolean isFound() {
        return found;
    }

    /**
     * Check if task is completed (terminal state).
     *
     * @return true if completed, false otherwise
     */
    public boolean isCompleted() {
        return completed;
    }

    /**
     * Check if task succeeded.
     *
     * @return true if status is COMPLETED, false otherwise
     */
    public boolean isSucceeded() {
        return ExtractionStatus.COMPLETED.equals(status);
    }

    /**
     * Check if task failed.
     *
     * @return true if status is FAILED or CANCELLED, false otherwise
     */
    public boolean isFailed() {
        return ExtractionStatus.FAILED.equals(status) || ExtractionStatus.CANCELLED.equals(status);
    }

    /**
     * Check if task was not found (404 error).
     *
     * @return true if task was not found, false otherwise
     */
    public boolean isNotFound() {
        return !found && throwable instanceof TaskNotFoundException;
    }

    @Data
    @Builder
    public static class FileInfo {
        private final String mimeType;
        private final String name;
        private final Integer pageCount;
        private final Integer ssCellCount;
        private final String url;
    }
}
