package com.tosspaper.purchaseorder;

import com.tosspaper.models.jooq.tables.pojos.PurchaseOrderItems;
import com.tosspaper.models.jooq.tables.records.PurchaseOrderFlatItemsRecord;
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord;
import com.tosspaper.purchaseorder.model.PurchaseOrderQuery;
import com.tosspaper.purchaseorder.model.ChangeLogEntry;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository {
    List<PurchaseOrdersRecord> find(long companyId, PurchaseOrderQuery query);
    int count(long companyId, PurchaseOrderQuery query);
    List<PurchaseOrderFlatItemsRecord> findById(String id);
    PurchaseOrdersRecord create(PurchaseOrdersRecord purchaseOrder, List<PurchaseOrderItems> items);
    PurchaseOrdersRecord update(PurchaseOrdersRecord purchaseOrder, List<PurchaseOrderItems> items, List<ChangeLogEntry> changes);
    PurchaseOrdersRecord updateStatus(String id, String status, ChangeLogEntry changeLogEntry);
    
    /**
     * Find purchase order by display ID and company's assigned email.
     * Joins purchase_orders with companies table for security.
     * 
     * @param displayId PO display ID
     * @param assignedEmail Company's assigned email
     * @return Optional containing the PO record if found
     */
    Optional<PurchaseOrdersRecord> findByDisplayIdAndAssignedEmail(String displayId, String assignedEmail);
    
    /**
     * Find purchase order by company ID and display ID.
     * 
     * @param companyId Company ID
     * @param displayId PO display ID
     * @return Optional containing the PO record if found
     */
    Optional<PurchaseOrdersRecord> findByCompanyIdAndDisplayId(Long companyId, String displayId);
    
    /**
     * Update purchase order status from PENDING to IN_PROGRESS when a document is approved.
     * Only updates if the PO is currently in PENDING status.
     * 
     * @param ctx DSL context for transaction
     * @param purchaseOrderId PO ID
     * @param companyId Company ID for security
     * @return true if status was updated, false otherwise
     */
    boolean updateStatusToInProgressIfPending(org.jooq.DSLContext ctx, String purchaseOrderId, Long companyId);
} 