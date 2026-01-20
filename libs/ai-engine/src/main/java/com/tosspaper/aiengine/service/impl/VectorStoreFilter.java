package com.tosspaper.aiengine.service.impl;

import lombok.Builder;
import lombok.Value;

/**
 * Filter for vector store queries.
 * Encapsulates filter conditions and generates expression string for vector store.
 */
@Value
@Builder
public class VectorStoreFilter {
    String assignedEmail;
    String purchaseOrderId;
    String partType;  // e.g., "line_item", "vendor_contact", "ship_to_contact"
    
    /**
     * Generate filter expression for vector store.
     * Format: "assignedEmail == 'value' AND purchaseOrderId == 'value' AND partType == 'value'"
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        
        if (assignedEmail != null) {
            sb.append("assignedEmail == '").append(assignedEmail).append("'");
        }
        
        if (purchaseOrderId != null) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append("purchaseOrderId == '").append(purchaseOrderId).append("'");
        }
        
        if (partType != null) {
            if (sb.length() > 0) {
                sb.append(" AND ");
            }
            sb.append("partType == '").append(partType).append("'");
        }
        
        return sb.toString();
    }
}

