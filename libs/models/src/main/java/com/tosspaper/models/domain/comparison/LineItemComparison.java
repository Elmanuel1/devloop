package com.tosspaper.models.domain.comparison;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents field-by-field comparison of line items.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LineItemComparison {
    
    private ComparisonField itemNo;
    private ComparisonField itemCode; // SKU, product code, material code (if present)
    private ComparisonField itemName;
    private ComparisonField quantity;
    private ComparisonField unit;
    private ComparisonField unitCost;
    private ComparisonField total;
    
    /**
     * Overall status for this line item (set by OpenAI).
     */
    private MatchStatus overallStatus;
    
    /**
     * Brief summary for this line item comparison (set by OpenAI).
     */
    private String summary;
}

