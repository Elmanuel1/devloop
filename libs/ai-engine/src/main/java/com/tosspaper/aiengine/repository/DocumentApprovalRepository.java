package com.tosspaper.aiengine.repository;

import com.tosspaper.models.domain.DocumentApproval;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.query.DocumentApprovalQuery;
import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for document approval operations.
 */
public interface DocumentApprovalRepository {
    /**
     * Find a document approval by company ID and approval ID (ULID).
     * Requires company ID for security - ensures users can only access approvals from their company.
     *
     * @param id the approval ULID
     * @return Optional containing the document approval if found
     */
    DocumentApproval findById(String id);
    
    /**
     * Find a document approval by assigned ID (extraction task ID).
     * Used for checking if approval record exists and for resync validation.
     *
     * @param assignedId the extraction task assigned ID
     * @return Optional containing the document approval if found
     */
    Optional<DocumentApproval> findByAssignedId(String assignedId);
    
    /**
     * Approve a document (pending/rejected → approved).
     * If already approved, this is idempotent.
     * If rejected, this allows approving it (rejected → approved).
     *
     * @param reviewedBy the user approving the document
     * @param notes optional notes explaining the approval
     */
    DocumentApproval approve(DSLContext ctx,  String approvalId, String projectId, String reviewedBy, String notes);

    /**
     * Reject a document (pending → rejected).
     * Cannot reject an already approved document.
     *
     * @param reviewedBy the user rejecting the document
     * @param notes optional notes explaining the rejection
     * @throws com.tosspaper.models.exception.BadRequestException if trying to reject an approved document
     */
    void reject(String approvalId, String reviewedBy, String notes);
    
    /**
     * Find document approvals by query.
     *
     * @param query the query parameters
     * @return list of document approvals
     */
    List<DocumentApproval> findByQuery(DocumentApprovalQuery query);
    
    /**
     * Find approved documents that need syncing for a connection.
     * Uses cursor-based pagination for efficiency.
     *
     * @param connectionId the integration connection ID
     * @param cursorAt     timestamp cursor (exclusive if cursorId provided, inclusive otherwise)
     * @param cursorId     ID cursor (tie-breaker for same timestamp)
     * @param limit        maximum number of documents to return
     * @return list of approved documents needing sync
     */
    List<DocumentApproval> findApprovedForSync(String connectionId, OffsetDateTime cursorAt, String cursorId, int limit);
    
    /**
     * Create an initial approval record immediately after document creation.
     * Called before PO matching. Creates approval with status='PENDING' and no match info.
     * This decouples approval creation from PO matching.
     */
    void createInitialApproval(
            DSLContext ctx,
            String poNumber,
            String documentNumber,
            String assignedId,
            Long companyId,
            String fromEmail,
            DocumentType documentType);

    /**
     * Create an auto-approved approval record for documents below the threshold.
     * Sets status to APPROVED with reviewed_by='SYSTEM'.
     *
     * @param ctx            the DSL context for transaction
     * @param poNumber       the PO number
     * @param documentNumber the document number
     * @param assignedId     the assigned ID
     * @param companyId      the company ID
     * @param fromEmail      the from email
     * @param documentType   the document type
     * @param projectId      the project ID (from PO)
     */
    void createAutoApprovedRecord(
            DSLContext ctx,
            String poNumber,
            String documentNumber,
            String assignedId,
            Long companyId,
            String fromEmail,
            DocumentType documentType,
            String projectId);
}

