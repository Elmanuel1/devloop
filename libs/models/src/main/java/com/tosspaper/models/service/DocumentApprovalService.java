package com.tosspaper.models.service;

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
}
