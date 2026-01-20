package com.tosspaper.aiengine.judge;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JSON-serializable verification report for UI consumption.
 * Contains comparison results with linkable indices for displaying matches and discrepancies.
 */
@Data
@Builder
public class ComparisonVerificationReport {

    /**
     * True if all judges passed (file exists, valid JSON, indices correct).
     * If false, the agent output is malformed and cannot be used.
     */
    private boolean structureValid;

    /**
     * Comparison status: "COMPLETE" (all items processed) or "ERROR" (structure invalid).
     */
    private String status;

    private Instant timestamp;

    // Summary counts
    private int totalDocumentItems;
    private int matchedItems;
    private int unmatchedItems;
    private int itemsWithDiscrepancies;

    // Detailed results
    private ContactComparison vendorContact;
    private ContactComparison shipToContact;
    private List<LineItemComparison> lineItems;

    // Verification checks from judges
    private List<VerificationCheck> checks;

    @Data
    @Builder
    public static class ContactComparison {
        private boolean matched;
        private Double matchScore;
        private String matchReasons;
        private Map<String, DiscrepancyDetail> discrepancies;
    }

    @Data
    @Builder
    public static class LineItemComparison {
        /**
         * Index in the extracted document's line items.
         */
        private int documentIndex;

        /**
         * Index in the PO's line items. Null if unmatched.
         */
        private Integer poIndex;

        private boolean matched;
        private Double matchScore;
        private String matchReasons;
        private Map<String, DiscrepancyDetail> discrepancies;
    }

    @Data
    @Builder
    public static class DiscrepancyDetail {
        private Object documentValue;
        private Object poValue;
        private Object difference;
    }

    @Data
    @Builder
    public static class VerificationCheck {
        private String name;
        private boolean passed;
        private String reasoning;
    }
}
