package com.tosspaper.document_approval;

import com.tosspaper.models.domain.DocumentApproval;

import java.time.OffsetDateTime;

/**
 * API-specific service for reviewing document match results.
 * Contains only API-specific methods (listDocumentApprovalsFromApi, reviewExtraction).
 */
public interface DocumentApprovalApiService {

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


