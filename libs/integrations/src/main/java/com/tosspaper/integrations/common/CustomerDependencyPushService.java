package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.integration.IntegrationConnection;

import java.util.List;

/**
 * Service for ensuring customers (ship-to locations) have external IDs before being used in other entities.
 * Auto-pushes customers to the provider if they lack external IDs.
 */
public interface CustomerDependencyPushService {

    /**
     * Ensures all customers have external IDs by auto-pushing to provider if needed.
     * Customers with existing external IDs are skipped.
     *
     * @param connection the integration connection
     * @param customers list of customers to check and push if needed
     * @return result indicating success or failure with error details
     */
    DependencyPushResult ensureHaveExternalIds(
        IntegrationConnection connection,
        List<Party> customers
    );
}
