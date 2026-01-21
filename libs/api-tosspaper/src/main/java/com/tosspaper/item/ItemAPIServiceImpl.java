package com.tosspaper.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.ForbiddenException;
import com.tosspaper.common.security.SecurityUtils;
import com.tosspaper.generated.model.Item;
import com.tosspaper.generated.model.ItemCreate;
import com.tosspaper.generated.model.ItemList;
import com.tosspaper.generated.model.ItemUpdate;
import com.tosspaper.integrations.push.IntegrationPushEvent;
import com.tosspaper.integrations.push.IntegrationPushStreamPublisher;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.integration.IntegrationCategory;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemAPIServiceImpl implements ItemAPIService {

    private final ItemRepository itemRepository;
    private final ItemMapper itemMapper;
    private final IntegrationConnectionService integrationConnectionService;
    private final IntegrationPushStreamPublisher integrationPushStreamPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public ItemList getItems(Long companyId, Boolean active) {
        log.debug("Fetching items for companyId: {}, active: {}", companyId, active);

        List<Item> domainItems = itemRepository.findByCompanyId(companyId)
                .stream()
                .filter(item -> !"Category".equals(item.getType()))
                .filter(item -> active == null || item.getActive().equals(active))
                .map(itemMapper::toApi)
                .toList();

        ItemList itemList = new ItemList();
        itemList.setData(domainItems);

        return itemList;
    }

    @Override
    public Item getItemById(Long companyId, String itemId) {
        log.debug("Fetching item by id: {} for companyId: {}", itemId, companyId);
        
        var item = itemRepository.findById(itemId);
        if (!item.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.ACCESS_DENIED_TO_ITEM);
        }
        
        return itemMapper.toApi(item);
    }

    @Override
    public Item createItem(Long companyId, ItemCreate itemCreate) {
        log.debug("Creating item for companyId: {}, name: {}", companyId, itemCreate.getName());
        
        var domainItem = itemMapper.toDomain(companyId, itemCreate);
        var createdItem = itemRepository.create(companyId, domainItem);
        
        log.info("Item created: id={}, name={}", createdItem.getId(), createdItem.getName());
        
        // Publish integration push event if there's an active connection
        publishIntegrationPushEventIfNeeded(createdItem, SecurityUtils.getSubjectFromJwt());
        
        return itemMapper.toApi(createdItem);
    }

    @Override
    public void updateItem(Long companyId, String itemId, ItemUpdate itemUpdate) {
        log.debug("Updating item: {} for companyId: {}", itemId, companyId);
        
        var existingItem = itemRepository.findById(itemId);
        if (!existingItem.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, ApiErrorMessages.ACCESS_DENIED_TO_ITEM);
        }
        
        itemMapper.updateDomain(itemUpdate, existingItem);
        var updatedItem = itemRepository.update(existingItem);
        
        log.info("Item updated: id={}, name={}", updatedItem.getId(), updatedItem.getName());
        
        // Publish integration push event if there's an active connection
        publishIntegrationPushEventIfNeeded(updatedItem, SecurityUtils.getSubjectFromJwt());
    }

    /**
     * Publish integration push event for item if there's an active ACCOUNTING connection.
     */
    private void publishIntegrationPushEventIfNeeded(com.tosspaper.models.domain.integration.Item item, String updatedBy) {
        try {
            Long companyId = item.getCompanyId();
            log.debug("Checking for active ACCOUNTING connection for company {} to publish item push event", companyId);
            
            // Get active ACCOUNTING connection for the company
            var connectionOpt = integrationConnectionService.findActiveByCompanyAndCategory(companyId, IntegrationCategory.ACCOUNTING);
            
            if (connectionOpt.isEmpty()) {
                log.debug("No active ACCOUNTING connection for company {}, skipping integration push", companyId);
                return;
            }
            
            IntegrationConnection connection = connectionOpt.get();
            log.info("Found active ACCOUNTING connection for company {}, publishing item push event", companyId);
            
            // Serialize Item to JSON payload
            String payload = objectMapper.writeValueAsString(item);
            
            IntegrationPushEvent event = new IntegrationPushEvent(
                    connection.getProvider(),
                    IntegrationEntityType.ITEM,
                    companyId,
                    connection.getId(),
                    payload,
                    updatedBy
            );
            
            integrationPushStreamPublisher.publish(event);
            log.info("Published item push event: id={}, name={}, provider={}", 
                    item.getId(), item.getName(), connection.getProvider());
            
        } catch (Exception e) {
            log.error("Failed to publish integration push event for item: id={}", item.getId(), e);
            // Don't throw - we don't want to fail item operations if push fails
        }
    }
}
