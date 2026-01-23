package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.TossPaperEntity;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the complete push flow: dependency resolution + entity push.
 * <p>
 * This service coordinates the interaction between DependencyCoordinator and
 * Push Providers,
 * eliminating circular dependencies by providing a single entry point for push
 * operations.
 * <p>
 * Architecture:
 * 
 * <pre>
 * Temporal Workflows
 *   ↓
 * IntegrationPushCoordinator (this class)
 *   ↓ (1) Ensure dependencies
 * DependencyCoordinator
 *   ↓ (2) Get provider
 * IntegrationProviderFactory
 *   ↓ (3) Push entity
 * Specific Push Provider (e.g., PurchaseOrderPushProvider)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationPushCoordinator {

    private final DependencyCoordinatorService dependencyCoordinator;
    private final IntegrationProviderFactory providerFactory;

    /**
     * Push a single entity with automatic dependency resolution.
     * <p>
     * This method:
     * 1. Ensures all dependencies (vendors, items, accounts) have external IDs
     * 2. Auto-pushes missing dependencies if needed
     * 3. Enriches the entity with dependency external IDs
     * 4. Pushes the entity to the provider
     *
     * @param connection integration connection
     * @param entityType type of entity being pushed
     * @param entity     the entity to push (must implement TossPaperEntity)
     * @return sync result
     */
    public <T extends TossPaperEntity> SyncResult pushWithDependencies(
            IntegrationConnection connection,
            IntegrationEntityType entityType,
            T entity) {

        log.debug("Coordinating push for {} entity to {}",
                entityType, connection.getProvider());

        // 1. Ensure all dependencies have external IDs (auto-push if needed)
        DependencyPushResult depResult = dependencyCoordinator.ensureAllDependencies(
                connection,
                entityType,
                List.of(entity));

        if (!depResult.isSuccess()) {
            log.warn("Dependency resolution failed for {} entity: {}",
                    entityType, depResult.getMessage());
            return SyncResult.failure(depResult.getMessage(), true);
        }

        // 2. Get provider for this entity type
        var providerOpt = providerFactory.getPushProvider(
                connection.getProvider(),
                entityType);

        if (providerOpt.isEmpty()) {
            return SyncResult.failure("No push provider available for " + entityType, false);
        }

        // 3. Push the entity (dependencies are now ready)
        @SuppressWarnings("unchecked")
        IntegrationPushProvider<Object> typedProvider = (IntegrationPushProvider<Object>) providerOpt.get();

        DocumentSyncRequest<Object> request = createSyncRequest(entity);
        return typedProvider.push(connection, request);
    }

    /**
     * Push multiple entities with automatic dependency resolution (batch
     * operation).
     * <p>
     * This method:
     * 1. Ensures all dependencies for ALL entities have external IDs
     * 2. Auto-pushes missing dependencies if needed (batch operation)
     * 3. Pushes each entity to the provider
     *
     * @param connection integration connection
     * @param entityType type of entities being pushed
     * @param entities   the entities to push (must implement TossPaperEntity)
     * @return map of entity ID to sync result
     */
    public <T extends TossPaperEntity> Map<String, SyncResult> pushBatchWithDependencies(
            IntegrationConnection connection,
            IntegrationEntityType entityType,
            List<T> entities) {

        Map<String, SyncResult> results = new HashMap<>();

        if (entities.isEmpty()) {
            return results;
        }

        log.info("Coordinating batch push of {} {} entities to {}",
                entities.size(), entityType, connection.getProvider());

        // 1. Ensure all dependencies have external IDs for the entire batch
        DependencyPushResult depResult = dependencyCoordinator.ensureAllDependencies(
                connection,
                entityType,
                entities);

        if (!depResult.isSuccess()) {
            log.warn("Dependency resolution failed for {} entities: {}",
                    entityType, depResult.getMessage());

            // Return failure for all entities
            for (T entity : entities) {
                results.put(entity.getId(), SyncResult.failure(depResult.getMessage(), true));
            }
            return results;
        }

        // 2. Get provider for this entity type
        var providerOpt = providerFactory.getPushProvider(
                connection.getProvider(),
                entityType);

        if (providerOpt.isEmpty()) {
            String errorMsg = "No push provider available for " + entityType;
            for (T entity : entities) {
                results.put(entity.getId(), SyncResult.failure(errorMsg, false));
            }
            return results;
        }

        // 3. Push each entity (dependencies are now ready)
        @SuppressWarnings("unchecked")
        IntegrationPushProvider<Object> typedProvider = (IntegrationPushProvider<Object>) providerOpt.get();

        for (T entity : entities) {
            String entityId = entity.getId();

            try {
                DocumentSyncRequest<Object> request = createSyncRequest(entity);
                SyncResult result = typedProvider.push(connection, request);
                results.put(entityId, result);
            } catch (Exception e) {
                log.error("Failed to push {} entity {}: {}",
                        entityType, entityId, e.getMessage(), e);
                results.put(entityId, SyncResult.failure(
                        "Push failed: " + e.getMessage(), true));
            }
        }

        long successCount = results.values().stream().filter(SyncResult::isSuccess).count();
        log.info("Batch push complete: {}/{} {} entities succeeded",
                successCount, entities.size(), entityType);

        return results;
    }

    /**
     * Create a DocumentSyncRequest wrapper for an entity.
     * Dispatches to the correct factory method based on entity type.
     */
    @SuppressWarnings("unchecked")
    private <T> DocumentSyncRequest<T> createSyncRequest(Object entity) {
        if (entity instanceof PurchaseOrder) {
            return (DocumentSyncRequest<T>) DocumentSyncRequest.fromPurchaseOrder((PurchaseOrder) entity);
        } else if (entity instanceof Party) {
            return (DocumentSyncRequest<T>) DocumentSyncRequest.fromVendor((Party) entity);
        } else if (entity instanceof Item) {
            return (DocumentSyncRequest<T>) DocumentSyncRequest.fromItem((Item) entity);
        } else if (entity instanceof Invoice) {
            return (DocumentSyncRequest<T>) DocumentSyncRequest.fromInvoice((Invoice) entity);
        } else {
            throw new IllegalArgumentException("Unsupported entity type: " + entity.getClass().getSimpleName());
        }
    }
}
