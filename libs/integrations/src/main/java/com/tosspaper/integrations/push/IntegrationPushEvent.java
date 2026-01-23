package com.tosspaper.integrations.push;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.models.domain.integration.IntegrationProvider;

/**
 * Event record for pushing entities to an integration provider.
 * Provider-agnostic - can be used for QuickBooks, Xero, or any other provider.
 */
public record IntegrationPushEvent(
        IntegrationProvider provider,
        IntegrationEntityType entityType,
        Long companyId,
        String connectionId,
        String payload,
        String updatedBy  // Optional: user ID or email who triggered the update

) { }
