package com.tosspaper.aiengine.dto.conformance;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from AI evaluator containing validation results and feedback.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EvaluationResponse {
    
    /**
     * Whether the generated JSON is valid against the schema.
     */
    @JsonProperty("isSchemaValid")
    private boolean isSchemaValid;
    
    /**
     * Quality score from 0.0 to 1.0.
     */
    @JsonProperty("score")
    private double score;
    
    /**
     * List of validation issues found.
     */
    @JsonProperty("issues")
    private List<ValidationIssue> issues;
    
    /**
     * Suggestions for improving the extraction in the next attempt.
     */
    @JsonProperty("suggestions")
    private String suggestions;
}
