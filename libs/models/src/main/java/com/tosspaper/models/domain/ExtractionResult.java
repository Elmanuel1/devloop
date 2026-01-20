package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Domain model for extraction results exposed to the frontend.
 * Contains raw extraction data that the user can review and modify before approval.
 */
@Value
@Builder
public class ExtractionResult {

    /**
     * Extraction task ID (assigned_id)
     */
    String assignedId;

    /**
     * Sender email address (from extraction_task.from_address)
     */
    String fromEmail;

    /**
     * Recipient email address (from extraction_task.to_address)
     */
    String toEmail;

    /**
     * Document type (invoice, delivery_slip, delivery_note)
     */
    String documentType;

    /**
     * Raw extraction results from provider API
     * (extraction_task.extract_task_results JSONB)
     * This is the unformatted response that contains all extraction data.
     */
    String extractionResult;

    String storageUrl;

    /**
     * Match type: pending, in_progress, direct, ai_match, no_match, manual, no_po_required
     */
    String matchType;

    /**
     * JSON string with AI comparison details
     */
    String matchReport;

    String poNumber;
    String poId;
    String projectId;
}
