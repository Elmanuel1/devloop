package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.models.domain.integration.IntegrationConnection;

import java.util.List;

/**
 * Strategy interface for handling dependencies of different entity types.
 * <p>
 * Each entity type (PO, Bill, Invoice, etc.) implements its own strategy
 * to ensure all dependencies have external IDs before pushing to the provider.
 * <p>
 * This follows the Strategy pattern and Open/Closed principle:
 * - Open for extension: add new entity types by creating new strategy implementations
 * - Closed for modification: no need to modify coordinator or other strategies
 */
public interface DependencyStrategy {

    /**
     * Checks if this strategy supports the given entity type.
     *
     * @param entityType the integration entity type
     * @return true if this strategy handles the entity type
     */
    boolean supports(IntegrationEntityType entityType);

    /**
     * Ensures all dependencies for the given entities have external IDs.
     * Auto-pushes dependencies to the provider if needed.
     *
     * @param connection the integration connection
     * @param entities list of entities (must cast to correct type based on supports())
     * @return result indicating success or failure with error details
     */
    DependencyPushResult ensureDependencies(
        IntegrationConnection connection,
        List<?> entities
    );
}
