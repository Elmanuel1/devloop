package com.tosspaper.models.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Domain model for Purchase Order Item.
 * Represents a line item within a purchase order.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseOrderItem {

    private String id;
    private String name;
    private String itemCode;        // SKU, product code, material code
    private Integer quantity;
    private String unit;
    private String unitCode;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private Boolean taxable;
    private LocalDate expectedDeliveryDate;
    private String deliveryStatus;
    private String notes;
    private Map<String, Object> metadata;

    // References to Items/Accounts for provider-agnostic line type support
    private String itemId;          // FK to items table (for item-based lines)
    private String accountId;       // FK to integration_accounts table (for account-based lines)
    
    // External references from provider (used during pull to resolve to internal IDs)
    private String externalItemId;     // Provider's item ID (e.g., QuickBooks ItemRef.value)
    private String externalAccountId;  // Provider's account ID (e.g., QuickBooks AccountRef.value)
}

