package com.tosspaper.integrations.quickbooks.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.integrations.utils.ProviderTrackingUtil;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps QuickBooks Item entities to domain Item models.
 */
@Component("integrationItemMapper")
@RequiredArgsConstructor
public class ItemMapper {

    private final ObjectMapper objectMapper;

    public Item toDomain(com.intuit.ipp.data.Item qboItem) {
        if (qboItem == null) {
            return null;
        }

        // Filter out Category items - they cannot be used in transactions
        if (qboItem.getType() != null && qboItem.getType() == com.intuit.ipp.data.ItemTypeEnum.CATEGORY) {
            return null;
        }

        Item item = Item.builder()
                .id(qboItem.getId())
                .name(qboItem.getName())
                .code(qboItem.getSku())
                .description(qboItem.getDescription())
                .type(qboItem.getType() != null ? qboItem.getType().value() : null)
                .unitPrice(qboItem.getUnitPrice())
                .purchaseCost(qboItem.getPurchaseCost())
                .active(qboItem.isActive())
                .taxable(qboItem.isTaxable())
                .quantityOnHand(qboItem.getQtyOnHand())
                .build();

        // Provider tracking (generic utility) - convert Date to OffsetDateTime
        Date createTime = qboItem.getMetaData() != null ? qboItem.getMetaData().getCreateTime() : null;
        Date lastUpdatedTime = qboItem.getMetaData() != null ? qboItem.getMetaData().getLastUpdatedTime() : null;

        ProviderTrackingUtil.populateProviderFields(
                item,
                IntegrationProvider.QUICKBOOKS.getValue(),
                qboItem.getId(),
                createTime != null ? OffsetDateTime.ofInstant(createTime.toInstant(), ZoneId.systemDefault()) : null,
                lastUpdatedTime != null ? OffsetDateTime.ofInstant(lastUpdatedTime.toInstant(), ZoneId.systemDefault()) : null
        );

        // Extract SyncToken for optimistic concurrency control
        item.setProviderVersion(qboItem.getSyncToken());

        // Store the full QBO Item so we have complete data for round-trip preservation.
        // Persisted as JSONB via external_metadata.
        try {
            Map<String, Object> externalMetadata = new HashMap<>();
            externalMetadata.put("qboEntity", objectMapper.writeValueAsString(qboItem));
            item.setExternalMetadata(externalMetadata);
        } catch (Exception ignored) {
            // Best-effort; mapping should still succeed even if serialization fails.
        }

        return item;
    }

    /**
     * Convert Item domain model to QBO Item.
     * First deserializes stored QBO entity from metadata (if exists) to preserve QB-only fields,
     * then applies domain values on top. Handles both CREATE and UPDATE.
     */
    public com.intuit.ipp.data.Item toQboItem(Item item) {
        if (item == null) {
            return null;
        }

        // STEP 1: Deserialize stored QBO entity (preserves QB-managed fields)
        com.intuit.ipp.data.Item qboItem = deserializeStoredQboEntity(item);

        // STEP 2: For UPDATE: set Id and SyncToken
        if (item.isUpdatable()) {
            qboItem.setId(item.getExternalId());
            qboItem.setSyncToken(item.getProviderVersion());
        }

        // STEP 3: Apply domain field changes
        applyDomainFieldsToItem(qboItem, item);

        return qboItem;
    }

    /**
     * Deserialize stored QBO Item from externalMetadata.
     * Returns empty Item if not found or deserialization fails.
     */
    private com.intuit.ipp.data.Item deserializeStoredQboEntity(Item item) {
        if (item.getExternalMetadata() == null) {
            return new com.intuit.ipp.data.Item();
        }
        Object qboEntityJson = item.getExternalMetadata().get("qboEntity");
        if (qboEntityJson == null) {
            return new com.intuit.ipp.data.Item();
        }
        try {
            return objectMapper.readValue(qboEntityJson.toString(), com.intuit.ipp.data.Item.class);
        } catch (Exception e) {
            return new com.intuit.ipp.data.Item();
        }
    }

    /**
     * Apply domain model fields to a QBO Item.
     * Only sets fields that are modifiable by users.
     */
    private void applyDomainFieldsToItem(com.intuit.ipp.data.Item qboItem, Item item) {
        // Name is required
        qboItem.setName(item.getName());

        // SKU/Code (optional) - Map item code to QuickBooks SKU
        if (item.getCode() != null) {
            qboItem.setSku(item.getCode());
        }

        // Description (optional)
        if (item.getDescription() != null) {
            qboItem.setDescription(item.getDescription());
        }

        // Type (convert string to enum)
        if (item.getType() != null) {
            qboItem.setType(com.intuit.ipp.data.ItemTypeEnum.fromValue(item.getType()));
        }

        // Pricing (optional)
        if (item.getUnitPrice() != null) {
            qboItem.setUnitPrice(item.getUnitPrice());
        }

        if (item.getPurchaseCost() != null) {
            qboItem.setPurchaseCost(item.getPurchaseCost());
        }

        // Active status (optional)
        if (item.getActive() != null) {
            qboItem.setActive(item.getActive());
        }

        // Taxable (optional)
        if (item.getTaxable() != null) {
            qboItem.setTaxable(item.getTaxable());
        }

        // Note: Don't set quantityOnHand - QB manages this based on transactions
    }
}
