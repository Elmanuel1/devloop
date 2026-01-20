package com.tosspaper.models.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tosspaper.models.domain.integration.ProviderTracked;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Domain model for Purchase Order.
 * Represents the business entity, not tied to database or API schemas.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PurchaseOrder extends ProviderTracked implements TossPaperEntity {

    private String id;
    private String displayId;
    private String projectId;
    private Long companyId;
    private LocalDate orderDate;
    private LocalDate dueDate;
    private PurchaseOrderStatus status;
    private Currency currencyCode;
    private Party vendorContact;
    private Party shipToContact;

    @Builder.Default
    private List<PurchaseOrderItem> items = new ArrayList<>();
    private Integer itemsCount;
    private String notes;
    private Map<String, Object> metadata;
    // provider, externalId, externalMetadata, providerVersion, providerCreatedAt, providerLastUpdatedAt inherited from ProviderTracked
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime deletedAt;
}

