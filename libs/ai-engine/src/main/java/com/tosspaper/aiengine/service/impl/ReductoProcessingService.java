package com.tosspaper.aiengine.service.impl;

import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult;
import com.tosspaper.aiengine.client.common.dto.PreparationResult;
import com.tosspaper.aiengine.client.common.dto.StartTaskResult;
import com.tosspaper.aiengine.dto.StartTaskRequest;
import com.tosspaper.aiengine.client.reducto.ReductoClient;
import com.tosspaper.aiengine.client.reducto.dto.ReductoAsyncExtractResponse;
import com.tosspaper.aiengine.client.reducto.dto.ReductoJobStatusResponse;
import com.tosspaper.aiengine.client.common.exception.StartTaskException;
import com.tosspaper.aiengine.client.common.exception.TaskNotFoundException;
import com.tosspaper.aiengine.client.reducto.dto.ReductoUploadResponse;
import com.tosspaper.aiengine.service.ProcessingService;
import com.tosspaper.models.domain.ExtractionStatus;
import com.tosspaper.models.domain.ExtractionTask;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Reducto AI implementation of ProcessingService.
 * Handles file preparation and task creation for Reducto AI.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReductoProcessingService implements ProcessingService {

    private static final String ERROR_TASK_SEARCH_NOT_SUPPORTED = "Task search not supported for Reducto";
    private static final String ERROR_FILE_SEARCH_NOT_SUPPORTED = "File search not supported for Reducto";

    private final ReductoClient reductoClient;
    
    /**
     * Determine document type from Reducto response.
     * Parses the result object to extract documentType field (camelCase per extraction.json schema).
     *
     * @param rawResponse The raw JSON response from Reducto
     * @param taskId The task ID for correlation in logs
     * @return The document type (invoice, delivery_slip, delivery_note, or unknown)
     */
    private String determineDocumentType(String rawResponse, String taskId) {
        try {
            if (rawResponse == null || rawResponse.trim().isEmpty()) {
                log.warn("Raw response is null or empty for taskId: {}, returning unknown document type", taskId);
                return "unknown";
            }

            // Parse the raw response JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(rawResponse);

            // Extract documentType from result.result.documentType.value (camelCase per extraction.json schema)
            com.fasterxml.jackson.databind.JsonNode resultNode = rootNode.get("result");
            if (resultNode != null) {
                com.fasterxml.jackson.databind.JsonNode innerResultNode = resultNode.get("result");
                if (innerResultNode != null) {
                    com.fasterxml.jackson.databind.JsonNode documentTypeNode = innerResultNode.get("documentType");
                    if (documentTypeNode != null && documentTypeNode.has("value")) {
                        String docType = documentTypeNode.get("value").asText();
                        log.debug("Extracted documentType from Reducto response for taskId: {} -> {}", taskId, docType);
                        return docType;
                    }
                }
            }

            log.warn("No documentType found in Reducto response for taskId: {}, returning unknown", taskId);
            return "unknown";

        } catch (Exception e) {
            log.error("Failed to parse documentType from raw response for taskId: {}", taskId, e);
            return "unknown";
        }
    }

    @Override
    public PreparationResult prepareTask(com.tosspaper.models.domain.FileObject fileObject) {
        log.info("Preparing file for Reducto AI: assignedId={}, fileName={}", 
                fileObject.getAssignedId(), fileObject.getFileName());
        
        Instant startedAt = Instant.now();
        
        // Upload to Reducto using presigned URL flow
        return Try.of(() -> {
            // Step 1: Request presigned URL
            ReductoUploadResponse uploadResponse = reductoClient.requestPresignedUrl();
            
            // Step 2: Upload file using presigned URL
            reductoClient.uploadFile(fileObject, uploadResponse.getPresignedUrl());
            
            Instant completedAt = Instant.now();
            log.info("Successfully prepared file for Reducto AI: assignedId={} -> preparationId: {} (took {}ms)", 
                    fileObject.getAssignedId(), uploadResponse.getFileId(), 
                    completedAt.toEpochMilli() - startedAt.toEpochMilli());
            return PreparationResult.success(uploadResponse.getFileId(), startedAt, completedAt);
        })
        .recover(Throwable.class, throwable -> {
            Instant completedAt = Instant.now();
            log.error("Preparation failed for Reducto AI: assignedId={}", 
                    fileObject.getAssignedId(), throwable);
            return PreparationResult.failure(
                "Preparation failed: " + throwable.getMessage(),
                throwable,
                startedAt,
                completedAt
            );
        })
        .get();
    }

    @Override
    public StartTaskResult startTask(StartTaskRequest request) {
        log.info("Starting Reducto AI extraction task for preparationId: {}, assignedId: {}", 
                request.getPreparationId(), request.getAssignedId());
        
        try {
            // Create async extract task using the provided schema and prompt
            // Note: preparationId is the assignedId from the FileObject
            ReductoAsyncExtractResponse taskResponse = reductoClient.createAsyncExtractTask(
                request.getPreparationId(),
                request.getSchema(),
                request.getPrompt()
            );
            
            log.info("Successfully started Reducto AI async extraction task: preparationId={} -> taskId: {}",
                    request.getPreparationId(), taskResponse.getJobId());
            
            return StartTaskResult.success(taskResponse.getJobId());
            
        } catch (StartTaskException e) {
            return StartTaskResult.failure(
                "Failed to start extraction task: " + e.getMessage(),
                e
            );
        } catch (Exception e) {
            log.error("Failed to start Reducto AI extraction task for preparationId: {}", request.getPreparationId(), e);
            return StartTaskResult.unknown(
                "Failed to start extraction task: " + e.getMessage(),
                e
            );
        }
    }

    @Override
    public ExtractTaskResult searchExecutionTask(ExtractionTask extractionTask) {
        String taskId = extractionTask.getTaskId();
        log.warn("Task search not supported for Reducto, returning not found for taskId: {}", taskId);

        return ExtractTaskResult.builder()
            .taskId(taskId)
            .found(false)
            .error(ERROR_TASK_SEARCH_NOT_SUPPORTED)
            .throwable(new TaskNotFoundException("Task not found for Reducto: " + taskId))
            .build();
    }

    @Override
    public ExtractTaskResult searchExecutionFile(ExtractionTask extractionTask) {
        String fileId = extractionTask.getPreparationId();
        log.info("File search not supported for Reducto, returning not found for file: {}", fileId);

        // Since Reducto doesn't have search endpoints, we return not found
        // This maintains interface compatibility but doesn't actually find existing files
        return ExtractTaskResult.builder()
            .fileId(fileId)
            .found(false)
            .error(ERROR_FILE_SEARCH_NOT_SUPPORTED)
            .throwable(new TaskNotFoundException("File not found for Reducto: " + fileId))
            .build();
    }

    @Override
    public ExtractTaskResult getExtractTask(String taskId) {
        log.info("Getting extract task from Reducto AI: taskId={}", taskId);
        
        try {
            ReductoJobStatusResponse jobStatus = reductoClient.getJobStatus(taskId);
            
            // Map Reducto status to our internal status using the DTO method
            ExtractionStatus mappedStatus = jobStatus.mapToInternalStatus();
            
            return ExtractTaskResult.builder()
                .taskId(taskId)
                .status(mappedStatus)
                .found(true)
                .rawResponse(jobStatus.getRawResponse())
                .type(determineDocumentType(jobStatus.getRawResponse(), taskId))
                .build();
                
        } catch (Exception e) {
            log.error("Failed to get extract task status from Reducto: taskId={}", taskId, e);
            return ExtractTaskResult.builder()
                .taskId(taskId)
                .found(false)
                .error("Failed to get extract task from Reducto: " + e.getMessage())
                .throwable(e)
                .build();
        }
    }
}
