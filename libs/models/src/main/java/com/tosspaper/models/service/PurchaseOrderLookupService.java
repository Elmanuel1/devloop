package com.tosspaper.models.service;

import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.PurchaseOrderStatus;

import java.util.Optional;

/**
 * Lookup service for Purchase Order operations.
 * Minimal interface for use by other modules (e.g., ai-engine) that need to
 * query or update PO status.
 */
public interface PurchaseOrderLookupService {

    /**
     * Basic purchase order information record.
     */
    record PurchaseOrderBasicInfo(String id, Long companyId, PurchaseOrderStatus status, String projectId,
            String poNumber) {
    }

    /**
     * Get a purchase order by ID.
     *
     * @param companyId the company ID (can be null to skip validation)
     * @param id        the purchase order ID
     * @return the purchase order with basic fields (id, companyId, status)
     */
    PurchaseOrderBasicInfo getPurchaseOrder(Long companyId, String id);

    /**
     * Find a purchase order by company ID and display ID.
     *
     * @param companyId the company ID
     * @param displayId the purchase order display ID
     * @return the purchase order if found
     */
    Optional<PurchaseOrderBasicInfo> findByCompanyIdAndDisplayId(Long companyId, String displayId);

    /**
     * Get a purchase order with all line items by display ID (poNumber).
     * Used for AI comparison where full item details are needed.
     *
     * @param companyId the company ID
     * @param poNumber  the purchase order display ID
     * @return the full purchase order with items if found
     */
    Optional<PurchaseOrder> getPoWithItemsByPoNumber(Long companyId, String poNumber);

    /**
     * Update the status of a purchase order.
     *
     * @param companyId the company ID
     * @param id        the purchase order ID
     * @param newStatus the new status enum
     * @param notes     optional notes explaining the status change
     * @param authorId  the user ID performing the update
     */
    void updatePurchaseOrderStatus(Long companyId, String id, PurchaseOrderStatus newStatus, String notes,
            String authorId);
}
