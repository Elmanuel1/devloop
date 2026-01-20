package com.tosspaper.models.domain;

import lombok.Getter;

/**
 * Internal extraction status enum.
 * Maps to external provider statuses.
 * This abstraction allows switching providers without changing business logic.
 */
@Getter
public enum ExtractionStatus {
    PENDING("pending"),
    /**
     * Preparation failed - cannot proceed with extraction.
     */
    PREPARE_FAILED("prepare_failed"),
    
    /**
     * Preparation succeeded - ready to start extraction.
     */
    PREPARE_SUCCEEDED("prepare_succeeded"),
    
    /**
     * Preparation status unknown - need to check status before proceeding.
     */
    PREPARE_UNKNOWN("prepare_unknown"),
    
    /**
     * Starting the extraction task failed.
     */
    START_TASK_FAILED("start_task_failed"),
    
    /**
     * Starting the extraction task failed with unknown error.
     */
    START_TASK_UNKNOWN("start_task_unknown"),
    
    /**
     * Extraction task has started and is running.
     */
    STARTED("started"),
    
    /**
     * Extraction task has completed successfully.
     */
    COMPLETED("completed"),
    
    /**
     * Task encountered an error and did not complete successfully.
     */
    FAILED("failed"),
    
    /**
     * Task was cancelled before completion.
     */
    CANCELLED("cancelled"),
    
    /**
     * Task requires manual intervention after exceeding retry limits.
     */
    MANUAL_INTERVENTION("manual_intervention");

    /**
     * -- GETTER --
     *  Get the display name for this status.
     *
     * @return human-readable status name
     */
    private final String displayName;
    
    ExtractionStatus(String displayName) {
        this.displayName = displayName;
    }

    public static ExtractionStatus fromDisplayName(String displayName) {
        for (ExtractionStatus status : values()) {
            if (status.displayName.equals(displayName)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown display name: " + displayName);
    }
    
    /**
     * Check if this status indicates failure.
     * 
     * @return true if failed, false otherwise
     */
    public boolean isFailed() {
        return this == FAILED || this == CANCELLED || this == PREPARE_FAILED || this == MANUAL_INTERVENTION;
    }

}
