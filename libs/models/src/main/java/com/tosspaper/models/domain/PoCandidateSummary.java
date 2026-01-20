package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Summary of a PO candidate for AI suggestion phase (Path 2).
 * Contains high-level PO info without detailed line item comparison.
 */
@Value
@Builder
public class PoCandidateSummary {
    String poId;
    String poDisplayId;
    String vendorName;
    String shipToName;
    int totalLineItems;
    double vectorSimilarityScore;    // From vector search distance (1.0 - distance)
}

