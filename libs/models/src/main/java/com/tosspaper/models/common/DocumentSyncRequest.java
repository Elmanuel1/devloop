package com.tosspaper.models.common;

import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.Item;
import lombok.Builder;
import lombok.Data;

/**
 * Request to sync a document to an external system.
 * Contains the document data and metadata needed for syncing.
 *
 * @param <T> the document type
 */
@Data
@Builder
public class DocumentSyncRequest<T> {

    private String documentId;
    private DocumentType documentType;
    private T document;
    private String poNumber;
    private String projectId;
    private String externalId;

    // Factory methods for domain models (used by bi-directional sync)

    /**
     * Create a sync request from an Invoice (to push as Bill to QuickBooks).
     */
    public static DocumentSyncRequest<Invoice> fromInvoice(Invoice invoice) {
        return DocumentSyncRequest.<Invoice>builder()
                .documentId(invoice.getAssignedId())
                .documentType(DocumentType.INVOICE)
                .document(invoice)
                .poNumber(invoice.getPoNumber())
                .projectId(invoice.getProjectId())
                .externalId(invoice.getExternalId())
                .build();
    }

    /**
     * Create a sync request from a PurchaseOrder.
     */
    public static DocumentSyncRequest<PurchaseOrder> fromPurchaseOrder(PurchaseOrder purchaseOrder) {
        return DocumentSyncRequest.<PurchaseOrder>builder()
                .documentId(purchaseOrder.getId())
                .documentType(DocumentType.PURCHASE_ORDER)
                .document(purchaseOrder)
                .externalId(purchaseOrder.getExternalId())
                .build();
    }

    /**
     * Create a sync request from a Party (Vendor).
     */
    public static DocumentSyncRequest<Party> fromVendor(Party vendor) {
        return DocumentSyncRequest.<Party>builder()
                .documentId(vendor.getId())
                .documentType(null) // Vendors don't have a DocumentType
                .document(vendor)
                .externalId(vendor.getExternalId())
                .build();
    }

    /**
     * Create a sync request from an Item.
     */
    public static DocumentSyncRequest<Item> fromItem(Item item) {
        return DocumentSyncRequest.<Item>builder()
                .documentId(item.getId())
                .documentType(null) // Items don't have a DocumentType
                .document(item)
                .externalId(item.getExternalId())
                .build();
    }
}
