package com.tosspaper.aiengine.repository;

import com.tosspaper.models.domain.ExtractionStatus;
import com.tosspaper.models.domain.ExtractionTask;
import org.jooq.DSLContext;

import java.util.Optional;

/**
 * Repository interface for extraction tasks.
 * Handles persistence of AI extraction job records.
 */
public interface ExtractionTaskRepository {
    
    /**
     * Save or update an extraction task.
     * If assignedId exists, updates attempts count and updatedAt.
     * If assignedId doesn't exist, creates new task.
     *
     * @param extractionTask the task to save
     * @return the saved/updated task
     */
    ExtractionTask save(ExtractionTask extractionTask);
    
    
    /**
     * Update an extraction task with expected status check.
     *
     * @param extractionTask the task to update
     * @param expectedStatus the expected current status for optimistic locking
     * @return the updated task
     * @throws RuntimeException if the current status doesn't match expected status
     */
    ExtractionTask update(ExtractionTask extractionTask, ExtractionStatus expectedStatus);

    ExtractionTask update(DSLContext dslContext, ExtractionTask extractionTask, ExtractionStatus expectedStatus);
    
    /**
     * Find an extraction task by task ID.
     *
     * @param taskId the task ID to search for
     * @return Optional containing the task if found, empty otherwise
     */
    Optional<ExtractionTask> findByTaskId(String taskId);
    
    /**
     * Find an extraction task by assigned ID.
     *
     * @param assignedId the assigned ID to search for
     * @return the task if found
     * @throws RuntimeException if not found
     */
    ExtractionTask findByAssignedId(String assignedId);

    void updateManualPoInformation(ExtractionTask extractionTask);
}
