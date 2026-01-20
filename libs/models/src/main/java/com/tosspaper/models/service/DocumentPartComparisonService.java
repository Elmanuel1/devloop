package com.tosspaper.models.service;

import com.tosspaper.models.domain.ComparisonContext;
import com.tosspaper.models.extraction.dto.Comparison;

import java.util.Optional;

/**
 * Service for comparing extracted document parts against stored PO parts.
 * This happens AFTER matching (finding the PO).
 * Compares: vendor contact, ship-to contact, and line items.
 */
public interface DocumentPartComparisonService {

    /**
     * Compare extracted document parts against a known PO and save results.
     * Used in Path 1 (immediate after direct match) and Path 2 (after user approves AI suggestion).
     *
     * Compares:
     * - Vendor contact
     * - Ship-to contact
     * - Each line item
     *
     * Returns the complete comparison result with all parts.
     * Results are automatically saved to the database.
     *
     * @param context ComparisonContext containing PurchaseOrder and ExtractionTask
     * @return Complete comparison result (vendor, ship-to, all line items)
     */
    Comparison compareDocumentParts(ComparisonContext context);

    /**
     * Get comparison result for an extraction task by assigned ID.
     *
     * @param assignedId the extraction task assigned ID
     * @param companyId the company ID for ownership verification
     * @return the comparison result if found
     */
    Optional<Comparison> getComparisonByAssignedId(String assignedId, Long companyId);

    /**
     * Manually trigger AI comparison for an extraction task with a linked PO.
     * This is used when a PO is manually linked and no automatic comparison has been run.
     *
     * Validates:
     * - Extraction task exists
     * - Company ownership
     * - PO is linked
     * - Conformed JSON is available
     *
     * Then:
     * - Deletes old comparison result (if any)
     * - Runs AI comparison between extracted document and linked PO
     * - Saves new comparison result to database
     *
     * @param assignedId the extraction task assigned ID
     * @param companyId the company ID for ownership verification
     * @throws com.tosspaper.models.exception.BadRequestException if validation fails
     * @throws RuntimeException if comparison fails
     */
    void manuallyTriggerComparison(String assignedId, Long companyId);
}
