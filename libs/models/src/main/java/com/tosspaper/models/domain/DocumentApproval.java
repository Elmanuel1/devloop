package com.tosspaper.models.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Domain model representing a document approval record.
 * Tracks approval workflow separately from document matching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentApproval {
    
    private String id; // ULID for cursor pagination
    private String assignedId; // FK to extraction_task.assigned_id
    private Long companyId; // FK to companies.id
    private String fromEmail;
    private String documentType;
    private String projectId; // nullable, from document_match
    private OffsetDateTime approvedAt; // nullable
    private OffsetDateTime rejectedAt; // nullable
    private String reviewedBy; // user who approved/rejected
    private String reviewNotes; // optional
    private String documentSummary; // description of what the document is about
    private String storageKey; // S3/storage key for the document file
    private OffsetDateTime createdAt;
    private String externalDocumentNumber;
    private String poNumber;
    private DocumentSyncStatus syncStatus; // null = not synced, PENDING, SYNCED, FAILED
    private OffsetDateTime lastSyncAttempt; // timestamp of last sync attempt (for retry tracking)
    
    /**
     * Check if this document approval has been approved.
     * @return true if approvedAt is not null
     */
    public boolean isApproved() {
        return approvedAt != null;
    }
    
    /**
     * Check if this document approval has been rejected.
     * @return true if rejectedAt is not null
     */
    public boolean isRejected() {
        return rejectedAt != null && approvedAt == null;
    }
    
    /**
     * Check if this document approval is pending (neither approved nor rejected).
     * @return true if both approvedAt and rejectedAt are null
     */
    public boolean isPending() {
        return approvedAt == null && rejectedAt == null;
    }
}

