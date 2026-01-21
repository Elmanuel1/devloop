package com.tosspaper.document_approval;

import com.tosspaper.models.extraction.dto.Extraction;
import lombok.SneakyThrows;

/**
 * Service for processing document approval events.
 * Orchestrates PO status updates and structured document creation.
 */
public interface DocumentApprovalEmailProcessingService {

    /**
     * Process a document approval event.
     * Updates PO status and creates structured invoice/delivery_slip records.
     *
     * @param assignedId the extracted information
     */
    void processDocumentApproval(String assignedId);
}

