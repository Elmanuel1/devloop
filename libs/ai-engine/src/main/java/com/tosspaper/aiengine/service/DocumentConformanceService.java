package com.tosspaper.aiengine.service;

import com.tosspaper.aiengine.dto.conformance.ConformanceResult;
import com.tosspaper.aiengine.factory.DocumentResourceFactory;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.aiengine.workflow.ConformanceEvaluatorOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for orchestrating document conformance processing.
 * Loads schemas and prompts, then delegates to the evaluator-optimizer workflow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentConformanceService {
    
    private final DocumentResourceFactory resourceFactory;
    private final ConformanceEvaluatorOptimizer evaluatorOptimizer;
    
    @Value("${conformance.success-threshold:0.95}")
    private double successThreshold;
    
    /**
     * Conform extracted document data to the specified schema.
     * 
     * @param rawExtraction The raw extracted data from AI processing
     * @param type The document type to determine schema and prompts
     * @return ConformanceResult with conformed JSON and metadata
     */
    public ConformanceResult conformDocument(String rawExtraction, DocumentType type) {
        log.info("Starting conformance for document type: {}", type);
        
        try {
            // Load resources for the document type
            String jsonSchema = resourceFactory.loadSchema(type);
            String generationPrompt = resourceFactory.loadGenerationPrompt(type);
            String evaluationPrompt = resourceFactory.loadEvaluationPrompt(type);
            
            // Run evaluator-optimizer workflow
            var result = evaluatorOptimizer.conform(
                rawExtraction, 
                jsonSchema, 
                generationPrompt, 
                evaluationPrompt
            );
            
            // Convert to ConformanceResult
            if (result.needsReview()) {
                return ConformanceResult.needsReview(
                    result.conformedJson(),
                    result.qualityScore(),
                    result.attemptCount(),
                    result.history().stream()
                        .flatMap(attempt -> attempt.issues().stream())
                        .toList()
                );
            } else {
                return ConformanceResult.success(
                    result.conformedJson(),
                    result.qualityScore(),
                    result.attemptCount()
                );
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to conform document", e);
        }
    }
}
