package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Domain model for extraction details including document matches
 */
@Value
@Builder
public class ExtractionDetail {
    String extractionTaskId;
    String storageKey;
    String purchaseOrderId;
    String matchType;
    String reviewStatus;
    String documentType;
    String extractTaskResults;
    String conformedJson;
    Double conformanceScore;
    String conformanceStatus;
    List<DocumentMatchDetail> documentMatches;
}

