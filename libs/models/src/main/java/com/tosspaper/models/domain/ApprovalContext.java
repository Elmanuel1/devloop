package com.tosspaper.models.domain;

import com.tosspaper.models.extraction.dto.Extraction;
import lombok.Builder;
import lombok.Value;

/**
 * Context object containing all data needed to process a document approval.
 * Generic type T represents the parsed document schema (InvoiceSchema or DeliverySlipSchema).
 */
@Value
@Builder(toBuilder = true)
public class ApprovalContext<T> {
    Extraction extraction;
    DocumentApproval documentApproval;
}


