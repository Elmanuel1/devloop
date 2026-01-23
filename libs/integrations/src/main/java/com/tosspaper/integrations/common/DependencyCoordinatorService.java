package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.models.domain.integration.IntegrationConnection;

import java.util.List;

/**
 * Facade service for coordinating dependency handling across different entity types.
 * Routes to entity-specific strategies for ensuring all dependencies have external IDs.
 * <p>
 * This follows the Facade pattern - providing a simple interface that delegates
 * to more complex subsystems (entity-specific strategies).
 */
public interface DependencyCoordinatorService {

    /**
     * Ensures all dependencies for the given entities have external IDs.
     * Auto-pushes dependencies to the provider if needed.
     * Routes to the appropriate strategy based on entity type.
     *
     * @param connection the integration connection
     * @param entityType the type of entities being processed
     * @param entities list of entities (PurchaseOrders, Bills, Invoices, etc.)
     * @return result indicating success or failure with error details
     */
    DependencyPushResult ensureAllDependencies(
        IntegrationConnection connection,
        IntegrationEntityType entityType,
        List<?> entities
    );
}
