package com.tosspaper.models.domain.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tosspaper.models.domain.TossPaperEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain model for Items (products/services) from external providers.
 * Items are connection-specific and used for ItemBasedExpenseLineDetail in PO lines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Item extends ProviderTracked implements TossPaperEntity {
    private String id;
    private Long companyId;
    private String connectionId;
    private String name;
    private String code;  // Optional item code/SKU, unique per company when provided
    private String description;
    private String type;  // "Inventory", "Service", "NonInventory"
    private BigDecimal unitPrice;
    private BigDecimal purchaseCost;
    private Boolean active;
    private Boolean taxable;
    private BigDecimal quantityOnHand;
    private OffsetDateTime createdAt;

    // Provider tracking fields inherited from ProviderTracked:
    // - provider (always QUICKBOOKS for now, but could be XERO, etc.)
    // - externalId
    // - externalMetadata
    // - providerCreatedAt
    // - providerLastUpdatedAt
    // - providerVersion (QuickBooks SyncToken for optimistic concurrency)
}
