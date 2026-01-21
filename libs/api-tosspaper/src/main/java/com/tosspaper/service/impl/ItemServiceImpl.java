package com.tosspaper.service.impl;

import com.tosspaper.item.ItemRepository;
import com.tosspaper.models.common.SyncStatusUpdate;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.service.ItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService {

    private final ItemRepository itemRepository;

    @Override
    public Item findById(String id) {
        return itemRepository.findById(id);
    }

    @Override
    public List<Item> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return itemRepository.findByIds(ids);
    }

    @Override
    public void upsertFromProvider(Long companyId, String connectionId, List<Item> items) {
        log.debug("Upserting {} items for company: {}, connection: {}", items.size(), companyId, connectionId);
        itemRepository.upsertFromProvider(companyId, connectionId, items);
    }

    @Override
    public Map<String, String> findIdsByExternalIdsAndConnection(String connectionId, List<String> externalIds) {
        return itemRepository.findIdsByExternalIdsAndConnection(connectionId, externalIds);
    }

    @Override
    public void updateSyncStatus(String itemId, String provider, String externalId, String providerVersion, java.time.OffsetDateTime providerLastUpdatedAt) {
        log.debug("Updating sync status for item: id={}, provider={}, externalId={}, providerVersion={}, providerLastUpdatedAt={}",
                itemId, provider, externalId, providerVersion, providerLastUpdatedAt);
        itemRepository.updateSyncStatus(itemId, provider, externalId, providerVersion, providerLastUpdatedAt);
    }

    @Override
    public void batchUpdateSyncStatus(List<SyncStatusUpdate> updates) {
        log.debug("Batch updating sync status for {} items", updates.size());
        itemRepository.batchUpdateSyncStatus(updates);
    }

    @Override
    public List<Item> findNeedingPush(Long companyId, String connectionId, int limit, int maxRetries) {
        log.debug("Finding items needing push for company: {}, connection: {}, limit: {}, maxRetries: {}",
                companyId, connectionId, limit, maxRetries);
        return itemRepository.findNeedingPush(companyId, connectionId, limit, maxRetries);
    }

    @Override
    public void clearSyncStatus(String itemId) {
        itemRepository.clearSyncStatus(itemId);
    }

    @Override
    public void incrementRetryCount(String itemId, String errorMessage) {
        itemRepository.incrementRetryCount(itemId, errorMessage);
    }

    @Override
    public void markAsPermanentlyFailed(String itemId, String errorMessage) {
        itemRepository.markAsPermanentlyFailed(itemId, errorMessage);
    }

    @Override
    public void resetRetryTracking(String itemId) {
        itemRepository.resetRetryTracking(itemId);
    }
}
