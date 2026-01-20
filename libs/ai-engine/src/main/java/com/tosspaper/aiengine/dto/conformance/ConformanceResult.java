package com.tosspaper.aiengine.dto.conformance;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of document conformance processing.
 */
@Data
@Builder
public class ConformanceResult {
    
    /**
     * Final conformed JSON matching the schema.
     */
    private String conformedJson;
    
    /**
     * Quality score from AI evaluation (0.0 to 1.0).
     */
    private double qualityScore;
    
    /**
     * Number of attempts made during conformance.
     */
    private int attemptCount;
    
    /**
     * Whether the result needs manual review.
     */
    private boolean needsReview;
    
    /**
     * List of issues found during conformance.
     */
    private List<String> issues;
    
    /**
     * Whether the JSON passed schema validation.
     */
    private boolean schemaValid;
    
    /**
     * List of schema validation errors (if any).
     */
    private List<String> schemaErrors;
    
    /**
     * Create a successful conformance result.
     */
    public static ConformanceResult success(String conformedJson, double qualityScore, int attemptCount) {
        return ConformanceResult.builder()
            .conformedJson(conformedJson)
            .qualityScore(qualityScore)
            .attemptCount(attemptCount)
            .needsReview(false)
            .issues(List.of())
            .build();
    }
    
    /**
     * Create a result that needs review.
     */
    public static ConformanceResult needsReview(String conformedJson, double qualityScore, 
                                               int attemptCount, List<String> issues) {
        return ConformanceResult.builder()
            .conformedJson(conformedJson)
            .qualityScore(qualityScore)
            .attemptCount(attemptCount)
            .needsReview(true)
            .issues(issues)
            .build();
    }
}
