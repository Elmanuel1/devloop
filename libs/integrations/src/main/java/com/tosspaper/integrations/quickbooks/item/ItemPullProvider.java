package com.tosspaper.integrations.quickbooks.item;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Pull provider for QuickBooks Items (products/services).
 * Items are PULL-ONLY - they are created and managed in QuickBooks,
 * then synced to our system for use in PO line items.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemPullProvider implements IntegrationPullProvider<Item> {

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
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<Item> pullBatch(IntegrationConnection connection) {
        OffsetDateTime lastSyncAt = connection.getLastSyncAt();

        String query = lastSyncAt == null
            ? "SELECT * FROM Item"
            : String.format(
                "SELECT * FROM Item WHERE MetaData.LastUpdatedTime > '%s'",
                lastSyncAt
            );

        List<com.intuit.ipp.data.Item> items = apiClient.executeQuery(connection, query);
        return items.stream()
            .map(itemMapper::toDomain)
            .filter(item -> item != null)
            .collect(Collectors.toList());
    }

    @Override
    public Item getById(String externalId, IntegrationConnection connection) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        String query = "SELECT * FROM Item WHERE Id = '" + externalId + "'";
        List<com.intuit.ipp.data.Item> items = apiClient.executeQuery(connection, query);
        return items.isEmpty() ? null : itemMapper.toDomain(items.getFirst());
    }
}
