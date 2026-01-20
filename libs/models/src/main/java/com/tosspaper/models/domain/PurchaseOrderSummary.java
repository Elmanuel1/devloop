package com.tosspaper.models.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Domain model representing a purchase order summary for matching purposes.
 * Contains only the essential fields needed for document matching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderSummary {
    
    private String id;
    private String displayId;
    private Long companyId;
    private String projectId;
    private String vendorContactId;
    private String vendorName;
    private String shipToContactId;
    private String shipToName;
    private LocalDate orderDate;
    private LocalDate dueDate;
    private String status;
}

