package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Implementation of DependencyCoordinatorService.
 * Routes to entity-specific strategies using Spring's auto-discovery.
 * <p>
 * Spring automatically injects all beans implementing DependencyStrategy,
 * enabling Open/Closed principle - add new entity types without modifying this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyCoordinatorServiceImpl implements DependencyCoordinatorService {

    private final List<DependencyStrategy> strategies;

    @Override
    public DependencyPushResult ensureAllDependencies(
            IntegrationConnection connection,
            IntegrationEntityType entityType,
            List<?> entities) {

        if (entities == null || entities.isEmpty()) {
            log.debug("No entities to process for {}", entityType);
            return DependencyPushResult.success();
        }

        log.debug("Finding strategy for entity type: {}", entityType);

        // Find the first strategy that supports this entity type
        DependencyStrategy strategy = strategies.stream()
            .filter(s -> s.supports(entityType))
            .findFirst()
            .orElse(null);

        if (strategy == null) {
            // No strategy found - this entity type doesn't have dependencies
            // This is not an error - some entities may not need dependency handling
            log.debug("No dependency strategy found for {}, assuming no dependencies needed", entityType);
            return DependencyPushResult.success();
        }

        log.info("Using {} to ensure dependencies for {} {} entities",
            strategy.getClass().getSimpleName(), entities.size(), entityType);

        return strategy.ensureDependencies(connection, entities);
    }
}
