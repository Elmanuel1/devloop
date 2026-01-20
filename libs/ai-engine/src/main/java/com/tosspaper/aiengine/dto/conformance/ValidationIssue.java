package com.tosspaper.aiengine.dto.conformance;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a validation issue found during conformance evaluation.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ValidationIssue {
    
    /**
     * Field path where the issue was found (e.g., "header.poNumber", "lineItems[0].quantity").
     */
    @JsonProperty("field")
    private String field;
    
    /**
     * Description of the problem found.
     */
    @JsonProperty("problem")
    private String problem;
    
    /**
     * Severity level: "critical" or "warning".
     */
    @JsonProperty("severity")
    private String severity;
    
    /**
     * Get severity as enum for type safety.
     */
    public Severity getSeverityEnum() {
        return "critical".equalsIgnoreCase(severity) ? Severity.CRITICAL : Severity.WARNING;
    }
    
    public enum Severity {
        CRITICAL, WARNING
    }
}
