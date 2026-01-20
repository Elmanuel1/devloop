package com.tosspaper.models.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain model for line items on documents (invoices, delivery slips, delivery notes).
 * Mirrors Charge fields from extraction.json plus flattened metadata from DeliveryTransaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineItem {
    // From Charge (extraction.json)
    private String lineNumber;
    private String itemCode;
    private String description;
    private String unitOfMeasure;
    private Double quantity;
    private Double unitPrice;
    private Double weight;

    // Flattened from DeliveryTransaction
    private String ticketNumber;   // from DeliveryTransaction.ticketId
    private String shipDate;

    // Calculated
    private Double total;

    // QuickBooks integration fields (enriched during bill creation)
    private String externalItemId;      // QuickBooks Item ID (for ItemBasedExpenseLineDetail)
    private String externalAccountId;   // QuickBooks Account ID (for AccountBasedExpenseLineDetail)
}

