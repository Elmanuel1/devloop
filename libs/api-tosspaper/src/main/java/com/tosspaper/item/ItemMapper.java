package com.tosspaper.item;

import com.tosspaper.generated.model.ItemCreate;
import com.tosspaper.generated.model.ItemUpdate;
import com.tosspaper.models.domain.integration.Item;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting Item domain models to API models and vice versa.
 */
@Component
public class ItemMapper {

    private static final String DEFAULT_TYPE = "Service";

    public com.tosspaper.generated.model.Item toApi(Item domain) {
        if (domain == null) {
            return null;
        }

        com.tosspaper.generated.model.Item api = new com.tosspaper.generated.model.Item();
        api.setId(domain.getId());
        api.setCompanyId(domain.getCompanyId());
        api.setConnectionId(domain.getConnectionId());
        api.setExternalId(domain.getExternalId());
        api.setName(domain.getName());
        api.setCode(domain.getCode());
        api.setDescription(domain.getDescription());
        api.setType(domain.getType());
        api.setUnitPrice(domain.getUnitPrice());
        api.setPurchaseCost(domain.getPurchaseCost());
        api.setActive(domain.getActive());
        api.setTaxable(domain.getTaxable());
        api.setQuantityOnHand(domain.getQuantityOnHand());

        if (domain.getCreatedAt() != null) {
            api.setCreatedAt(domain.getCreatedAt());
        }

        return api;
    }

    /**
     * Convert ItemCreate DTO to domain model.
     * Sets default values: type = "Service", active = true
     */
    public Item toDomain(Long companyId, ItemCreate create) {
        if (create == null) {
            return null;
        }

        return Item.builder()
                .companyId(companyId)
                .name(create.getName())
                .code(create.getCode())
                .description(create.getDescription())
                .type(DEFAULT_TYPE)
                .purchaseCost(create.getPurchaseCost())
                .active(true)
                .build();
    }

    /**
     * Update existing domain model with values from ItemUpdate DTO.
     * Only updates non-null fields.
     */
    public void updateDomain(ItemUpdate update, Item existing) {
        if (update == null || existing == null) {
            return;
        }

        if (update.getName() != null) {
            existing.setName(update.getName());
        }
        if (update.getCode() != null) {
            existing.setCode(update.getCode());
        }
        if (update.getDescription() != null) {
            existing.setDescription(update.getDescription());
        }
        if (update.getPurchaseCost() != null) {
            existing.setPurchaseCost(update.getPurchaseCost());
        }
        if (update.getActive() != null) {
            existing.setActive(update.getActive());
        }
    }
}
