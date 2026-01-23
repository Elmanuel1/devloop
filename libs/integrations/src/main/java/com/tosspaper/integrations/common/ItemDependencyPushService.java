package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.Item;

import java.util.List;

/**
 * Service for ensuring items have external IDs before being used in other entities.
 * Auto-pushes items to the provider if they lack external IDs.
 */
public interface ItemDependencyPushService {

    /**
     * Ensures all items have external IDs by auto-pushing to provider if needed.
     * Items with existing external IDs are skipped.
     *
     * @param connection the integration connection
     * @param items list of items to check and push if needed
     * @return result indicating success or failure with error details
     */
    DependencyPushResult ensureHaveExternalIds(
        IntegrationConnection connection,
        List<Item> items
    );
}
