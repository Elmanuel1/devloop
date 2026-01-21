package com.tosspaper.document_approval;

import com.tosspaper.models.domain.DocumentApproval;
import com.tosspaper.models.query.DocumentApprovalQuery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for reviewing document match results and querying document approvals.
 * Handles approval/rejection of extraction matches and coordinates PO status updates.
 */
public interface DocumentApprovalService {

    DocumentApproval findById(String id);

    /**
     * Find document approvals by query.
     *
     * @param query the query parameters
     * @return list of document approvals
     */
    List<DocumentApproval> findByQuery(DocumentApprovalQuery query);
    
    /**
     * Find a document approval by assigned ID (extraction task ID).
     * Used for checking if approval record exists and for resync validation.
     *
     * @param assignedId the extraction task assigned ID
     * @return Optional containing the document approval if found
     */
    Optional<DocumentApproval> findByAssignedId(String assignedId);
    
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
     * List document approvals from API parameters.
     * Handles cursor decoding, query building, and returns API response.
     *
     * @param companyId the company ID
     * @param pageSize the page size
     * @param cursor the cursor string (base64 encoded)
     * @param status the status filter
     * @param documentType the document type filter
     * @param fromEmail the from email filter
     * @param createdDateFrom the created date from filter
     * @param createdDateTo the created date to filter
     * @param projectId the project ID filter
     * @return API response with document approvals and pagination
     */
    DocumentApprovalListResponse listDocumentApprovalsFromApi(
        Long companyId,
        Integer pageSize,
        String cursor,
        String status,
        String documentType,
        String fromEmail,
        OffsetDateTime createdDateFrom,
        OffsetDateTime createdDateTo,
        String projectId
    );

    /**
     * Review a document approval from API parameters with document data.
     * When approved, documentData must be provided and will be used to create the document.
     * When rejected, documentData is optional.
     *
     * @param companyId the company ID
     * @param approvalId the approval ID (ULID)
     * @param approved whether the approval is approved (true) or rejected (false)
     * @param reviewedBy the user ID performing the review
     * @param reviewNotes optional review notes
     * @param documentData the document data from user (required when approved=true)
     */
    void reviewExtraction(Long companyId, String approvalId, boolean approved, String reviewedBy, String reviewNotes, com.tosspaper.models.extraction.dto.Extraction documentData);

    /**
     * Response object for list document approvals API.
     */
    record DocumentApprovalListResponse(
        java.util.List<DocumentApproval> data,
        String nextCursor
    ) {}
}


