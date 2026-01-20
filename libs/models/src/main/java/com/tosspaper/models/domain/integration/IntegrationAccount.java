package com.tosspaper.models.domain.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Domain model for Chart of Accounts from external providers.
 * Connection-specific (not company-wide) since different connections may have different account structures.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntegrationAccount extends ProviderTracked {
    private String id;
    private String connectionId;
    private String name;
    private String accountType;
    private String accountSubType;
    private String classification;
    private Boolean active;
    private BigDecimal currentBalance;
    private OffsetDateTime createdAt;
    
    // Provider tracking fields inherited from ProviderTracked:
    // - provider (always QUICKBOOKS for now, but could be XERO, etc.)
    // - externalId
    // - externalMetadata
    // - providerCreatedAt
    // - providerLastUpdatedAt
    // No version/pushedVersion (PULL-only, never pushed)
}
