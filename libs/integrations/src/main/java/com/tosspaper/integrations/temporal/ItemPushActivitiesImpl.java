package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.service.ItemService;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of ItemPushActivities for pushing items to QuickBooks.
 */
@Slf4j
@Component
@ActivityImpl(taskQueues = "integration-sync")
@RequiredArgsConstructor
public class ItemPushActivitiesImpl implements ItemPushActivities {

    private final IntegrationConnectionService connectionService;
    private final ItemService itemService;
    private final IntegrationProviderFactory providerFactory;
    private final com.tosspaper.integrations.config.PushRetryConfig pushRetryConfig;

    @Override
    public SyncConnectionData getConnection(String connectionId) {
        IntegrationConnection connection = connectionService.findById(connectionId);
        if (connection == null || !connection.isEnabled()) {
            throw new IntegrationException("Integration connection not found or not enabled: " + connectionId);
        }
        IntegrationConnection activeConnection = connectionService.ensureActiveToken(connection);
        return SyncConnectionData.from(activeConnection);
    }

    @Override
    public List<Item> fetchItemsNeedingPush(SyncConnectionData connection, int limit) {
        log.debug("Fetching items needing push: companyId={}, connectionId={}, limit={}, maxRetries={}",
                connection.getCompanyId(), connection.getId(), limit, pushRetryConfig.getMaxAttempts());

        List<Item> items = itemService.findNeedingPush(
                connection.getCompanyId(),
                connection.getId(),
                limit,
                pushRetryConfig.getMaxAttempts());

        if (items.isEmpty()) {
            log.debug("Found {} items needing push", items.size());
        } else {
            log.info("Found {} items needing push", items.size());
        }
        return items;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushItems(SyncConnectionData connection, List<Item> items) {
        Map<String, SyncResult> results = new HashMap<>();

        if (items.isEmpty()) {
            return results;
        }

        log.info("Pushing {} items to provider: {}", items.size(), connection.getProvider());

        // Get push provider
        var pushProviderOpt = providerFactory.getPushProvider(
                connection.getProvider(),
                IntegrationEntityType.ITEM);

        if (pushProviderOpt.isEmpty()) {
            log.warn("No item push provider found for: {}", connection.getProvider());
            for (Item item : items) {
                results.put(item.getId(), SyncResult.failure("No push provider available", false));
            }
            return results;
        }

        IntegrationPushProvider<Item> pushProvider = (IntegrationPushProvider<Item>) pushProviderOpt.get();

        // Fetch fresh connection with tokens (not from Temporal history)
        IntegrationConnection integrationConnection = getConnectionWithTokens(connection);

        // Convert to DocumentSyncRequest list
        List<DocumentSyncRequest<?>> requests = items.stream()
                .<DocumentSyncRequest<?>>map(DocumentSyncRequest::fromItem)
                .collect(java.util.stream.Collectors.toList());

        // Push in batch
        results = pushProvider.pushBatch(integrationConnection, requests);

        long successfulCount = results.values().stream().filter(SyncResult::isSuccess).count();
        long failedCount = results.size() - successfulCount;

        log.info("Pushed {} items with {} successful and {} failed",
                items.size(), successfulCount, failedCount);

        // Log details of failed pushes
        if (failedCount > 0) {
            results.entrySet().stream()
                    .filter(entry -> !entry.getValue().isSuccess())
                    .forEach(entry -> {
                        SyncResult result = entry.getValue();
                        log.warn("Item {} push failed: {} (retryable: {})",
                                entry.getKey(),
                                result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error",
                                result.isRetryable());
                    });
        }

        return results;
    }

    /**
     * Get IntegrationConnection with fresh tokens for API calls.
     * Re-fetches from database to ensure tokens are current and not from Temporal history.
     */
    private IntegrationConnection getConnectionWithTokens(SyncConnectionData connectionData) {
        IntegrationConnection connection = connectionService.findById(connectionData.getId());
        if (connection == null || !connection.isEnabled()) {
            throw new IntegrationException("Integration connection not found or not enabled: " + connectionData.getId());
        }
        return connectionService.ensureActiveToken(connection);
    }

    @Override
    public int markItemsAsPushed(String provider, Map<String, SyncResult> results) {
        int markedCount = 0;

        for (Map.Entry<String, SyncResult> entry : results.entrySet()) {
            String itemId = entry.getKey();
            SyncResult result = entry.getValue();

            // Early exit: Success case
            if (result.isSuccess()) {
                try {
                    itemService.updateSyncStatus(
                            itemId,
                            provider,
                            result.getExternalId(),
                            result.getProviderVersion(),
                            result.getProviderLastUpdatedAt());
                    markedCount++;
                } catch (Exception e) {
                    log.error("Failed to mark item {} as pushed", itemId, e);
                }
                continue;
            }

            // Early exit: Non-retryable failures (conflicts, duplicate names)
            if (!result.isRetryable()) {
                try {
                    itemService.markAsPermanentlyFailed(itemId, result.getErrorMessage());
                    log.warn("Item {} marked as permanently failed (non-retryable): {}",
                            itemId, result.getErrorMessage());
                } catch (Exception e) {
                    log.error("Failed to mark item {} as permanently failed", itemId, e);
                }
                continue;
            }

            // Retryable errors: increment retry count
            try {
                itemService.incrementRetryCount(itemId, result.getErrorMessage());

                // Check if exceeded max retries
                Item item = itemService.findById(itemId);
                if (item != null && item.getPushRetryCount() != null &&
                    item.getPushRetryCount() >= pushRetryConfig.getMaxAttempts()) {
                    itemService.markAsPermanentlyFailed(
                            itemId,
                            String.format("Exceeded max retries (%d). Last error: %s",
                                    pushRetryConfig.getMaxAttempts(),
                                    result.getErrorMessage()));
                    log.warn("Item {} exceeded max retries and marked permanently failed", itemId);
                }
            } catch (Exception e) {
                log.error("Failed to increment retry count for item {}", itemId, e);
            }
        }

        log.info("Marked {} items as successfully pushed", markedCount);
        return markedCount;
    }
}
