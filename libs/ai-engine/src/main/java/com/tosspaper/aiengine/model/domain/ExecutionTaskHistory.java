package com.tosspaper.aiengine.model.domain;

import com.tosspaper.models.domain.ExtractionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Domain model for execution task history.
 * Tracks individual execution attempts for extraction tasks.
 */
@Data
@Builder(toBuilder = true)
public class ExecutionTaskHistory {
    
    /**
     * Unique identifier for the execution history record.
     */
    private String id;
    
    /**
     * Reference to the extraction task.
     */
    private String extractionTaskId;
    
    /**
     * Sequential attempt number for this task.
     */
    private Integer attemptNumber;
    
    /**
     * Status of this specific execution attempt.
     */
    private ExtractionStatus status;
    
    /**
     * Error message if this attempt failed.
     */
    private String errorMessage;
    
    /**
     * When this execution attempt started.
     */
    private OffsetDateTime startedAt;
    
    /**
     * When this execution attempt completed.
     */
    private OffsetDateTime completedAt;
    
    /**
     * When this history record was created.
     */
    private OffsetDateTime createdAt;
}
