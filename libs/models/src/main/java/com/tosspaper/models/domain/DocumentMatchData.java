package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Document match data from invoice or delivery_slip tables.
 * Contains match-related fields needed for approval detail view.
 */
@Value
@Builder
public class DocumentMatchData {
    String matchType;
    String purchaseOrderId;
    String poNumber;
    String projectId;
    String matchReport;
    String documentStatus;
}