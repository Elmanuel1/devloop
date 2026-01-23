package com.tosspaper.integrations.quickbooks.item;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ItemPushProvider implements IntegrationPushProvider<Item> {

    private final QuickBooksApiClient apiClient;
    private final ItemMapper itemMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.ITEM;
    }

    @Override
    public DocumentType getDocumentType() {
        throw new UnsupportedOperationException("ItemPushProvider does not support DocumentType - items are not documents");
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public SyncResult push(IntegrationConnection connection, DocumentSyncRequest<Item> request) {
        return push(connection, request.getDocument());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushBatch(IntegrationConnection connection, List<DocumentSyncRequest<?>> batch) {
        try {
            List<com.intuit.ipp.data.Item> qboItems = batch.stream()
                    .map(req -> (Item) req.getDocument())
                    .map(itemMapper::toQboItem)
                    .collect(Collectors.toList());

            List<QuickBooksApiClient.BatchResult<com.intuit.ipp.data.Item>> batchResults =
                    apiClient.saveBatch(connection, qboItems);

            Map<String, SyncResult> results = new HashMap<>();

            // Map results to document IDs
            for (int i = 0; i < batch.size(); i++) {
                DocumentSyncRequest<?> request = batch.get(i);
                String documentId = request.getDocumentId();

                if (i < batchResults.size()) {
                    QuickBooksApiClient.BatchResult<com.intuit.ipp.data.Item> result = batchResults.get(i);
                    if (result.success()) {
                        results.put(documentId, SyncResult.builder()
                                .success(true)
                                .externalId(result.entity().getId())
                                .providerVersion(result.entity().getSyncToken())
                                .build());
                    } else {
                        // Detect stale object errors and convert to conflict
                        String errorMsg = result.errorMessage();
                        boolean isStaleError = errorMsg != null && 
                            (errorMsg.toLowerCase().contains("stale") || 
                             errorMsg.toLowerCase().contains("synctoken"));
                        
                        if (isStaleError) {
                            results.put(documentId, SyncResult.conflict(errorMsg));
                        } else {
                            results.put(documentId, SyncResult.failure(errorMsg, true));
                        }
                    }
                } else {
                    results.put(documentId, SyncResult.failure("No result returned from batch", false));
                }
            }

            return results;

        } catch (Exception e) {
            log.error("Item batch push failed", e);
            Map<String, SyncResult> errorResults = new HashMap<>();
            for (DocumentSyncRequest<?> request : batch) {
                errorResults.put(request.getDocumentId(),
                        SyncResult.failure("Batch push error: " + e.getMessage(), true));
            }
            return errorResults;
        }
    }

    /**
     * Push a single item to QuickBooks.
     * Handles both CREATE (new item) and UPDATE (existing item) operations.
     */
    public SyncResult push(IntegrationConnection connection, Item item) {
        try {
            com.intuit.ipp.data.Item qboItem = itemMapper.toQboItem(item);

            log.debug("{} item {} in QuickBooks",
                    item.isUpdatable() ? "Updating" : "Creating",
                    item.isUpdatable() ? item.getExternalId() : item.getName());

            com.intuit.ipp.data.Item result = apiClient.save(connection, qboItem);

            return SyncResult.builder()
                    .success(true)
                    .externalId(result.getId())
                    .providerVersion(result.getSyncToken())
                    .build();

        } catch (ProviderVersionConflictException e) {
            log.warn("Sync token conflict for item {}: {}", item.getId(), e.getMessage());
            return SyncResult.conflict("Entity modified in QuickBooks - please refresh and retry");
        } catch (com.tosspaper.models.exception.DuplicateException e) {
            log.warn("Duplicate name error for item {}: {}", item.getId(), e.getMessage());
            return SyncResult.conflict("Duplicate name - another item is using this name in QuickBooks");
        } catch (Exception e) {
            log.error("Failed to push item to QuickBooks", e);
            return SyncResult.failure("Failed to push item: " + e.getMessage(), true);
        }
    }
}
