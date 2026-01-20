package com.tosspaper.models.domain.comparison;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a field-level comparison between PO and document values.
 * poValue, docType, and docValue are set by the application.
 * status and summary are set by OpenAI during comparison.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComparisonField {
    
    /**
     * Value from the Purchase Order (can be null).
     */
    private String poValue;
    
    /**
     * Document type being compared (e.g., "invoice", "delivery_slip").
     */
    private String docType;
    
    /**
     * Value from the extracted document (can be null).
     */
    private String docValue;
    
    /**
     * Comparison status (set by OpenAI).
     */
    private MatchStatus status;
    
    /**
     * Brief explanation of the comparison result (set by OpenAI).
     */
    private String summary;
}

