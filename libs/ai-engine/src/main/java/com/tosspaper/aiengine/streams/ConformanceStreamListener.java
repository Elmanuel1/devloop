package com.tosspaper.aiengine.streams;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.dto.conformance.ConformanceResult;
import com.tosspaper.models.domain.ConformanceStatus;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.aiengine.service.DocumentConformanceService;
import com.tosspaper.aiengine.service.ExtractionRagService;
import com.tosspaper.aiengine.service.JsonSchemaValidationService;
import com.tosspaper.models.domain.ExtractionTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamListener;

import java.time.OffsetDateTime;

/**
 * Redis Stream listener for processing document conformance requests.
 * Handles classification and conformance processing asynchronously.
 * DISABLED: Conformance processing is now handled directly in ExtractionService.processExtractedDocument()
 * to avoid the need for a separate conformance stream.
 */
@Slf4j
// @Component // DISABLED - conformance processing now happens directly in ExtractionService
@RequiredArgsConstructor
public class ConformanceStreamListener implements StreamListener<String, MapRecord<String, String, String>> {
    
    private final ExtractionTaskRepository repository;
    private final DocumentConformanceService conformanceService;
    private final ExtractionRagService extractionRagService;
    private final JsonSchemaValidationService jsonSchemaValidationService;
    private final ObjectMapper objectMapper;

    @Value("${conformance.processing-timeout-hours:1}")
    private int processingTimeoutHours;
    
    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String assignedId = message.getValue().get("assignedId");
        log.info("Processing conformance request for: {}", assignedId);
        
        try {
            // Load ExtractionTask
            ExtractionTask task = repository.findByAssignedId(assignedId);
            if (task == null) {
                log.error("ExtractionTask not found: {}", assignedId);
                return;
            }
            
            // Skip if already validated or processing
            if (task.getConformanceStatus() == ConformanceStatus.VALIDATED) {
                log.info("Task {} already validated, skipping conformance processing", assignedId);
                return;
            }
            
            // Mark as NEEDS_REVIEW if document type is UNKNOWN
            if (task.getDocumentType() == null || task.getDocumentType() == DocumentType.UNKNOWN) {
                log.warn("Document type is UNKNOWN for {}, marking as NEEDS_REVIEW", assignedId);
                task = task.toBuilder()
                    .conformanceStatus(ConformanceStatus.NEEDS_REVIEW)
                    .build();
                repository.update(task, task.getStatus());
                return;
            }
            
            // Retrieve extraction content from vector store
            String extractionContent = extractionRagService.getExtractionContentById(assignedId);
            if (extractionContent == null || extractionContent.trim().isEmpty()) {
                log.warn("Cannot conform document {}: no extraction content in vector store", assignedId);
                return;
            }
            
            // Update status to PROCESSING
            task = task.toBuilder()
                .conformanceStatus(ConformanceStatus.PROCESSING)
                .build();

            repository.update(task, task.getStatus());
            
            // Conform document using extraction from vector store
            ConformanceResult result = conformanceService.conformDocument(
                extractionContent, task.getDocumentType());
            
            // Validate conformed JSON against schema
            ConformanceStatus finalStatus;
            String finalEvaluation;
            
            if (result.getConformedJson() != null && !result.getConformedJson().trim().isEmpty()) {
                JsonSchemaValidationService.ValidationResult validation = 
                    jsonSchemaValidationService.validate(result.getConformedJson(), task.getDocumentType());
                
                if (!validation.isValid()) {
                    // Schema validation failed - needs review
                    log.warn("Schema validation failed for {}: {}", assignedId, validation.getErrors());
                    finalStatus = ConformanceStatus.NEEDS_REVIEW;
                    finalEvaluation = serializeValidationErrors(result, validation);
                } else if (result.isNeedsReview()) {
                    // AI says needs review even though schema is valid
                    log.info("AI evaluation requires review for {}", assignedId);
                    finalStatus = ConformanceStatus.NEEDS_REVIEW;
                    finalEvaluation = serializeEvaluation(result);
                } else {
                    // Both AI and schema validation passed
                    log.info("Conformance and schema validation passed for {}", assignedId);
                    finalStatus = ConformanceStatus.VALIDATED;
                    finalEvaluation = serializeEvaluation(result);
                }
            } else {
                // No conformed JSON produced
                log.warn("No conformed JSON produced for {}", assignedId);
                finalStatus = ConformanceStatus.NEEDS_REVIEW;
                finalEvaluation = serializeEvaluation(result);
            }
            
            // Update task with results
            ExtractionTask updated = task.toBuilder()
                .conformedJson(result.getConformedJson())
                .conformanceScore(result.getQualityScore())
                .conformanceStatus(finalStatus)
                .conformanceAttempts(result.getAttemptCount())
                .conformanceHistory(serializeHistory(result))
                .conformanceEvaluation(finalEvaluation)
                .conformedAt(OffsetDateTime.now())
                .build();
            
            repository.update(updated, task.getStatus());
            
            log.info("Conformance completed for {}: score={}, status={}", 
                assignedId, result.getQualityScore(), updated.getConformanceStatus());
            
            // If conformance succeeded, create invoice/delivery_slip record and publish to PO matching stream
            if (finalStatus == ConformanceStatus.VALIDATED) {
                // DISABLED - this listener is no longer used
                // Document creation now happens directly in ExtractionService
                log.debug("Conformance validated for extraction task: {} (document creation disabled in this listener)", assignedId);
            }
            
        } catch (Exception e) {
            log.error("Conformance failed for {}", assignedId, e);
            throw new RuntimeException("Failed to process conformance request", e);
        }
    }
    
    private String serializeHistory(ConformanceResult result) {
        try {
            // For now, serialize issues as simple JSON array
            return objectMapper.writeValueAsString(result.getIssues());
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize conformance history", e);
            return "[]";
        }
    }
    
    private String serializeEvaluation(ConformanceResult result) {
        try {
            // Create a simple evaluation object
            var evaluation = new EvaluationSummary(
                result.getQualityScore(),
                result.isNeedsReview(),
                result.getIssues(),
                result.isSchemaValid(),
                result.getSchemaErrors()
            );
            return objectMapper.writeValueAsString(evaluation);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize conformance evaluation", e);
            return "{}";
        }
    }
    
    private String serializeValidationErrors(ConformanceResult result, 
                                            JsonSchemaValidationService.ValidationResult validation) {
        try {
            // Include both AI evaluation and schema validation errors
            var evaluation = new EvaluationSummary(
                result.getQualityScore(),
                true, // needs review due to schema validation failure
                result.getIssues(),
                false, // schema validation failed
                validation.getErrors()
            );
            return objectMapper.writeValueAsString(evaluation);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize validation errors", e);
            return "{}";
        }
    }
    
    private record EvaluationSummary(
        double score,
        boolean needsReview,
        java.util.List<String> issues,
        boolean schemaValid,
        java.util.List<String> schemaErrors
    ) {}
}
