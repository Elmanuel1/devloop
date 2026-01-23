package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.common.SyncStatusUpdate;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of ItemDependencyPushService.
 * Auto-pushes items to provider if they lack external IDs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemDependencyPushServiceImpl implements ItemDependencyPushService {

    private final ItemService itemService;
    private final IntegrationProviderFactory providerFactory;

    @Override
    public DependencyPushResult ensureHaveExternalIds(
            IntegrationConnection connection,
            List<Item> items) {

        // Filter items needing push (those without externalId)
        List<Item> needsPush = items.stream()
            .filter(item -> item.getExternalId() == null)
            .toList();

        if (needsPush.isEmpty()) {
            log.debug("All {} items already have external IDs, skipping push", items.size());
            return DependencyPushResult.success();
        }

        log.info("Auto-pushing {} items (out of {}) to {} to obtain external IDs",
            needsPush.size(), items.size(), connection.getProvider());

        // Get provider-specific push provider
        IntegrationPushProvider<Item> pushProvider = providerFactory
            .getPushProvider(connection.getProvider(), IntegrationEntityType.ITEM)
            .map(provider -> (IntegrationPushProvider<Item>) provider)
            .orElseThrow(() -> new IllegalStateException(
                "No item push provider found for " + connection.getProvider()));

        // Batch push items
        List<DocumentSyncRequest<?>> requests = needsPush.stream()
            .<DocumentSyncRequest<?>>map(DocumentSyncRequest::fromItem)
            .collect(Collectors.toList());

        try {
            Map<String, SyncResult> results = pushProvider.pushBatch(connection, requests);

            // Process results and prepare batch updates
            List<SyncStatusUpdate> batchUpdates = new ArrayList<>();

            for (Item item : needsPush) {
                SyncResult result = results.get(item.getId());

                if (result == null) {
                    String errorMsg = String.format(
                        "No result returned for item %s (id=%s)",
                        item.getName(), item.getId());
                    log.error(errorMsg);
                    return DependencyPushResult.failure(errorMsg);
                }

                if (result.isSuccess()) {
                    // Add to batch updates
                    batchUpdates.add(new SyncStatusUpdate(
                        item.getId(),
                        connection.getProvider().getValue(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt()
                    ));

                    // Update in-memory object so enricher can use it immediately
                    item.setProvider(connection.getProvider().getValue());
                    item.setExternalId(result.getExternalId());
                    item.setProviderVersion(result.getProviderVersion());
                    item.setProviderLastUpdatedAt(result.getProviderLastUpdatedAt());

                    log.debug("Successfully pushed item {} to {}, externalId={}",
                        item.getName(), connection.getProvider(), result.getExternalId());
                } else {
                    String errorMsg = String.format(
                        "Failed to push item %s (id=%s) to %s: %s",
                        item.getName(), item.getId(),
                        connection.getProvider(), result.getErrorMessage());
                    log.error(errorMsg);
                    return DependencyPushResult.failure(errorMsg);
                }
            }

            // Batch update all successful syncs
            itemService.batchUpdateSyncStatus(batchUpdates);
        } catch (Exception e) {
            String errorMsg = String.format(
                "Exception during batch push of %d items to %s: %s",
                needsPush.size(), connection.getProvider(), e.getMessage());
            log.error(errorMsg, e);
            return DependencyPushResult.failure(errorMsg);
        }

        log.info("Successfully auto-pushed {} items to {}",
            needsPush.size(), connection.getProvider());
        return DependencyPushResult.success();
    }
}
