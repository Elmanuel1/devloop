package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;

/**
 * Domain model for document match details used in API responses.
 * Simplified view of DocumentMatch for external consumption.
 */
@Value
@Builder
public class DocumentMatchDetail {
    String extractionTaskId;
    String documentType;
    String fromEmail;
    String toEmail;
    OffsetDateTime receivedAt;
    String matchType;
    String matchReport; // JSON string containing array of CandidateComparison objects
    String reviewStatus;
    String reviewedBy;
    OffsetDateTime reviewedAt;
    OffsetDateTime createdAt;
    OffsetDateTime updatedAt;
}

