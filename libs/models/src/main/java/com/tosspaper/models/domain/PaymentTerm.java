package com.tosspaper.models.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tosspaper.models.domain.integration.ProviderTracked;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain model for Payment Terms.
 * Can be synced from external providers or created locally.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentTerm extends ProviderTracked {
    private UUID id;
    private Long companyId;
    private String name;
    private Integer dueDays;
    private BigDecimal discountPercent;
    private Integer discountDays;
    private Boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    
    // Provider tracking fields inherited from ProviderTracked:
    // - provider
    // - externalId
    // - externalMetadata
    // - providerCreatedAt
    // - providerLastUpdatedAt
    // No version/pushedVersion (PULL-only, never pushed)
}
