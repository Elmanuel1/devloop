package com.tosspaper.models.domain;

/**
 * Context object holding the PO and ExtractionTask for comparison advisors.
 */
public record ComparisonContext(
        PurchaseOrder purchaseOrder,
        ExtractionTask extractionTask
) {
}