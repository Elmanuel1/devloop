package com.tosspaper.aiengine.service;

import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult;
import com.tosspaper.aiengine.client.common.dto.PreparationResult;
import com.tosspaper.aiengine.client.common.dto.StartTaskResult;
import com.tosspaper.aiengine.dto.StartTaskRequest;
import com.tosspaper.models.domain.ExtractionTask;

/**
 * Interface for AI processing providers.
 * Abstracts the preparation and execution of extraction tasks.
 */
public interface ProcessingService {
    
    /**
     * Prepare file for extraction (provider-specific preprocessing).
     * Returns preparation result with status and timing information.
     *
     * @param fileObject the file object to prepare
     * @return preparation result with status, timing, and preparation ID
     * @throws Exception if preparation fails
     */
    PreparationResult prepareTask(com.tosspaper.models.domain.FileObject fileObject) throws Exception;
    
    /**
     * Start extraction task with the prepared file.
     * Returns result with task ID on success or error information on failure.
     *
     * @param request the start task request containing preparationId, schema, prompt, and assignedId
     * @return start task result with task ID or error
     */
    StartTaskResult startTask(StartTaskRequest request);
    
    /**
     * Search for execution tasks by extraction task object.
     * Uses date range search to find tasks in a focused time window.
     *
     * @param extractionTask the extraction task object containing file ID and task ID
     * @return extract task result with file ID and task ID
     */
    ExtractTaskResult searchExecutionTask(ExtractionTask extractionTask);

    /**
     * Search for execution files by extraction task object.
     * Uses date range search to find files in a focused time window.
     *
     * @param extractionTask the extraction task object containing file ID
     * @return extract task result with file ID
     * @throws Exception if the search fails
     */
    ExtractTaskResult searchExecutionFile(ExtractionTask extractionTask) throws Exception;

    /**
     * Get extract task by task ID.
     * Direct lookup by ID for tasks that are already started.
     *
     * @param taskId the task ID to get
     * @return extract task result with full task details
     */
    ExtractTaskResult getExtractTask(String taskId);
}
