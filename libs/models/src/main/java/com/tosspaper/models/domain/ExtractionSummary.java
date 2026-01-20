package com.tosspaper.models.domain;

import java.time.OffsetDateTime;

import lombok.Builder;
import lombok.Value;

/**
 * Domain representation of the extraction summary row rendered in dashboards.
 */
@Value
@Builder(toBuilder = true)
public class ExtractionSummary {

    String assignedId;
    ExtractionSummaryBucket bucket;

    String extractionStatus;
    String conformanceStatus;
    Double conformanceScore;
    String documentType;
    String matchType;
    String matchReviewStatus;
    String purchaseOrderId;
    String projectId;

    String fromEmail;
    String toEmail;
    String subject;
    String storageKey;
    String conformedJson;

    OffsetDateTime receivedAt;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
    OffsetDateTime conformedAt;

    Boolean hasConformedJson;
    String reason;
}


