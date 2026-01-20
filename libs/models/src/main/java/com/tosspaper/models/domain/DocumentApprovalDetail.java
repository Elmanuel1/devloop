package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;

/**
 * Complete document approval detail for review page.
 * Combines data from document_approvals, extraction_task, and invoice/delivery_slip tables.
 */
@Value
@Builder
public class DocumentApprovalDetail {

    // Approval metadata
    String approvalId;
    String assignedId; // extraction_task_id
    Long companyId;
    String documentType; // "invoice" or "delivery_slip"

    // Email metadata (from extraction_task)
    String fromEmail;
    String toEmail;
    String emailSubject;
    OffsetDateTime receivedAt;

    // Extraction metadata (from extraction_task)
    String extractionStatus;
    String conformanceStatus;
    Double conformanceScore;
    OffsetDateTime conformedAt;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;

    // Extracted document content (from extraction_task.conformed_json)
    String conformedJson;

    // Match information (from invoice/delivery_slip)
    String matchType; // pending, in_progress, direct, ai_match, no_match, manual, no_po_required
    String purchaseOrderId;
    String poNumber;
    String projectId;
    String externalDocumentNumber;
    String matchReport; // JSON string with AI comparison details

    // Document status (from invoice/delivery_slip)
    String documentStatus; // draft, accepted, rejected

    // Approval status (from document_approvals)
    OffsetDateTime approvedAt;
    OffsetDateTime rejectedAt;
    String reviewedBy;
    String reviewNotes;
    String documentSummary;
    String storageKey;
}