package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.integration.IntegrationConnection;

import java.util.List;

/**
 * Service for ensuring vendors have external IDs before being used in other entities.
 * Auto-pushes vendors to the provider if they lack external IDs.
 */
public interface VendorDependencyPushService {

    /**
     * Ensures all vendors have external IDs by auto-pushing to provider if needed.
     * Vendors with existing external IDs are skipped.
     *
     * @param connection the integration connection
     * @param vendors list of vendors to check and push if needed
     * @return result indicating success or failure with error details
     */
    DependencyPushResult ensureHaveExternalIds(
        IntegrationConnection connection,
        List<Party> vendors
    );
}
