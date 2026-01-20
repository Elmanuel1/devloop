package com.tosspaper.aiengine.model.domain;

import com.tosspaper.models.domain.ExtractionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Represents a single page task within the extraction_task JSONB pages array.
 * Each page has its own processing status and results.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PageTask {
    
    /**
     * Page number within the document (1-based).
     */
    private Integer pageNumber;

    private String assignedId;
    private String storageUrl;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private OffsetDateTime updatedAt;
    
    /**
     * Preparation ID from the AI provider for this page.
     */
    private String preparationId;
    
    /**
     * Task ID from the AI provider for this page.
     */
    private String taskId;
    
    /**
     * Processing status for this page.
     */
    private ExtractionStatus status;
    
    /**
     * Number of processing attempts for this page.
     */
    private Integer attempts;
    
    /**
     * When extraction started for this page.
     */
    private OffsetDateTime extractionStartedAt;

    private OffsetDateTime preparationStartedAt;
    
    /**
     * Raw result from the AI provider (JSON string).
     */
    private String result;
    
    /**
     * Error message if processing failed.
     */
    private String errorMessage;
    
    /**
     * Check if this page task is in a terminal state.
     */
    public boolean isTerminal() {
        return status == ExtractionStatus.COMPLETED || 
               status == ExtractionStatus.FAILED || 
               status == ExtractionStatus.CANCELLED;
    }
    
    /**
     * Check if this page task can be retried.
     */
    public boolean canRetry() {
        return status == ExtractionStatus.PENDING || 
               status == ExtractionStatus.PREPARE_FAILED ||
               status == ExtractionStatus.START_TASK_FAILED;
    }
    
    /**
     * Increment the attempts counter.
     */
    public void incrementAttempts() {
        this.attempts = (this.attempts != null ? this.attempts : 0) + 1;
    }
}
