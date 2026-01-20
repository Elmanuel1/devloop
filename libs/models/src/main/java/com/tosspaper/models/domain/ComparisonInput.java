package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Input for document part comparison.
 * Contains formatted text content and metadata for tracking.
 * Supports vendor contact, ship-to contact, and line items.
 */
@Value
@Builder
public class ComparisonInput {
    String partType;             // "vendor_contact", "ship_to_contact", "line_item"
    Integer itemIndex;           // NULL for contacts, 0-based for line items
    String textContent;          // Key-value formatted part
    String extractionId;
    String extractedPartName;    // Contact name or line item name
    String extractedPartDescription;  // For line items
}

