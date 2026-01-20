package com.tosspaper.aiengine.repository;

import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.MatchType;

/**
 * Repository for managing document match state transitions.
 * All methods handle transactions internally for atomic updates to invoices/delivery_slips.
 */
public interface DocumentMatchRepository {

    /**
     * Updates document match_type to IN_PROGRESS.
     * Used when AI matching or manual linking is initiated.
     *
     * @param assignedId the extraction task assigned ID
     * @param documentType the document type (INVOICE or DELIVERY_SLIP)
     */
    void updateToInProgress(String assignedId, DocumentType documentType);

    /**
     * Updates document with manual match information.
     * Sets match_type=MANUAL and stores match report with AI comparison results.
     *
     * @param assignedId the extraction task assigned ID
     * @param documentType the document type (INVOICE or DELIVERY_SLIP)
     * @param matchReport JSON string with AI comparison results
     * @param poId the purchase order ID
     * @param poNumber the purchase order display number
     * @param projectId the project ID from the PO
     */
    void updateToManual(String assignedId, DocumentType documentType,
                       String matchReport, String poId, String poNumber, String projectId);

    /**
     * Updates document match_type back to PENDING.
     * Used when resetting a document for rematch (e.g., user rejects and wants to retry).
     *
     * @param assignedId the extraction task assigned ID
     * @param documentType the document type (INVOICE or DELIVERY_SLIP)
     */
    void updateToPending(String assignedId, DocumentType documentType);

    /**
     * Update document and extraction_task with match information.
     * Documents and approval records are already created by ExtractionService.
     * This method only updates match-related fields from PO matching.
     *
     * @param assignedId the extraction task ID
     * @param documentType the document type
     * @param matchType the match type (DIRECT, AI_MATCH, MANUAL, NO_MATCH)
     * @param matchReport the match report JSON string
     * @param purchaseOrderId the matched purchase order ID (can be null for NO_MATCH)
     * @param poNumber the matched purchase order number (can be null for NO_MATCH)
     * @param projectId the matched project ID (can be null for NO_MATCH)
     */
    void updateMatchInfo(
        String assignedId,
        DocumentType documentType,
        MatchType matchType,
        String matchReport,
        String purchaseOrderId,
        String poNumber,
        String projectId
    );
}