package com.tosspaper.integrations.common;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of pulling a purchase order from an external system.
 */
@Data
@Builder
public class PurchaseOrderSyncResult {

    private boolean success;
    private String externalId;
    private String externalDocNumber;
    private String errorMessage;

    // PO data from external system
    private String vendorExternalId;
    private String vendorName;
    private LocalDate issueDate;
    private LocalDate expectedDeliveryDate;
    private BigDecimal totalAmount;
    private String currency;
    private String status;  // OPEN, CLOSED, etc.

    private List<LineItem> lineItems;

    @Data
    @Builder
    public static class LineItem {
        private String externalId;
        private String description;
        private BigDecimal quantity;
        private String unitOfMeasure;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }

    public static PurchaseOrderSyncResult failure(String errorMessage) {
        return PurchaseOrderSyncResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}
