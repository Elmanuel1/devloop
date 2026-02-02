package com.tosspaper.models.domain;

/**
 * Context object holding the PO, ExtractionTask, and session ID for comparison.
 *
 * @param purchaseOrder The purchase order to compare against
 * @param extractionTask The extraction task containing the document to compare
 * @param comparisonId Unique session ID for isolation (format: {assignedId}-{uuid})
 */
public record ComparisonContext(
        PurchaseOrder purchaseOrder,
        ExtractionTask extractionTask,
        String comparisonId
) {
    /**
     * Create context without comparisonId (for blocking/legacy calls).
     */
    public ComparisonContext(PurchaseOrder purchaseOrder, ExtractionTask extractionTask) {
        this(purchaseOrder, extractionTask, null);
    }
}