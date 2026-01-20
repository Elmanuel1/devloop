package com.tosspaper.models.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
/**
 * Domain model representing a document match result.
 * Tracks how extracted documents (invoices, delivery slips) match to purchase orders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentMatch {
    
    private String extractionTaskId; // Primary key - References extraction_task.assigned_id
    private String documentType;
    private String fromEmail;
    private String toEmail;
    private String emailSubject;
    private OffsetDateTime receivedAt;
    
    // Match results
    private String purchaseOrderId; // ID of matched PO (for easier querying)
    private String poNumber; // PO display_id (for denormalization to extraction_task)
    private String projectId; // Project ID from matched PO (for easier querying)
    private MatchType matchType; // direct, similarity_match, ai_match, manual, no_match 
    private String matchReport; // JSONB stored as String
    
    // Review workflow
    private ReviewStatus reviewStatus;
    private String reviewedBy;
    private OffsetDateTime reviewedAt;
    private String reviewNotes;
    
    // Timestamps
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

