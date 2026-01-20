package com.tosspaper.models.domain.comparison;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Represents the complete detailed comparison between a PO and a document.
 * Contains field-by-field comparisons for all relevant fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetailedComparison {
    
    /**
     * Overall comparison score (0.0 to 1.0, set by OpenAI).
     */
    private BigDecimal score;
    
    /**
     * Brief summary of the overall comparison (set by OpenAI).
     */
    private String summary;
    
    /**
     * Supplier/vendor contact comparison.
     */
    private ContactComparison supplier;
    
    /**
     * Ship-to contact comparison.
     */
    private ContactComparison shipTo;
    
    /**
     * Supplied-to/bill-to contact comparison.
     */
    private ContactComparison suppliedTo;
    
    /**
     * Line items comparison.
     */
    private List<LineItemComparison> lineItems;
}

