package com.tosspaper.item;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.DuplicateException;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.common.SyncStatusUpdate;
import com.tosspaper.models.domain.integration.Item;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tosspaper.models.jooq.Tables.ITEMS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ItemRepositoryImpl implements ItemRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public void upsert(Long companyId, String connectionId, List<Item> items) {
        for (Item item : items) {
            dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, companyId)
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, item.getExternalId())
                .set(ITEMS.NAME, item.getName())
                .set(ITEMS.DESCRIPTION, item.getDescription())
                .set(ITEMS.TYPE, item.getType())
                .set(ITEMS.UNIT_PRICE, item.getUnitPrice())
                .set(ITEMS.PURCHASE_COST, item.getPurchaseCost())
                .set(ITEMS.ACTIVE, item.getActive())
                .set(ITEMS.TAXABLE, item.getTaxable())
                .set(ITEMS.QUANTITY_ON_HAND, item.getQuantityOnHand())
                .set(ITEMS.EXTERNAL_METADATA, JSONB.jsonbOrNull(objectMapper.writeValueAsString(item.getExternalMetadata())))
                .set(ITEMS.PROVIDER_CREATED_AT, item.getProviderCreatedAt())
                .set(ITEMS.PROVIDER_LAST_UPDATED_AT, item.getProviderLastUpdatedAt())
                .set(ITEMS.LAST_SYNC_AT, java.time.OffsetDateTime.now())
                .onConflict(ITEMS.CONNECTION_ID, ITEMS.EXTERNAL_ID)
                .doUpdate()
                .set(ITEMS.NAME, org.jooq.impl.DSL.excluded(ITEMS.NAME))
                .set(ITEMS.DESCRIPTION, org.jooq.impl.DSL.excluded(ITEMS.DESCRIPTION))
                .set(ITEMS.TYPE, org.jooq.impl.DSL.excluded(ITEMS.TYPE))
                .set(ITEMS.UNIT_PRICE, org.jooq.impl.DSL.excluded(ITEMS.UNIT_PRICE))
                .set(ITEMS.PURCHASE_COST, org.jooq.impl.DSL.excluded(ITEMS.PURCHASE_COST))
                .set(ITEMS.ACTIVE, org.jooq.impl.DSL.excluded(ITEMS.ACTIVE))
                .set(ITEMS.TAXABLE, org.jooq.impl.DSL.excluded(ITEMS.TAXABLE))
                .set(ITEMS.QUANTITY_ON_HAND, org.jooq.impl.DSL.excluded(ITEMS.QUANTITY_ON_HAND))
                .set(ITEMS.EXTERNAL_METADATA, org.jooq.impl.DSL.excluded(ITEMS.EXTERNAL_METADATA))
                .set(ITEMS.PROVIDER_LAST_UPDATED_AT, org.jooq.impl.DSL.excluded(ITEMS.PROVIDER_LAST_UPDATED_AT))
                .set(ITEMS.LAST_SYNC_AT, org.jooq.impl.DSL.excluded(ITEMS.LAST_SYNC_AT))
                .execute();
        }
    }

    @Override
    @SneakyThrows
    public void upsertFromProvider(Long companyId, String connectionId, List<Item> items) {
        for (Item item : items) {
            dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, companyId)
                .set(ITEMS.CONNECTION_ID, connectionId)
                .set(ITEMS.EXTERNAL_ID, item.getExternalId())
                .set(ITEMS.NAME, item.getName())
                .set(ITEMS.DESCRIPTION, item.getDescription())
                .set(ITEMS.TYPE, item.getType())
                .set(ITEMS.UNIT_PRICE, item.getUnitPrice())
                .set(ITEMS.PURCHASE_COST, item.getPurchaseCost())
                .set(ITEMS.ACTIVE, item.getActive())
                .set(ITEMS.TAXABLE, item.getTaxable())
                .set(ITEMS.QUANTITY_ON_HAND, item.getQuantityOnHand())
                .set(ITEMS.EXTERNAL_METADATA, JSONB.jsonbOrNull(objectMapper.writeValueAsString(item.getExternalMetadata())))
                .set(ITEMS.PROVIDER_CREATED_AT, item.getProviderCreatedAt())
                .set(ITEMS.PROVIDER_LAST_UPDATED_AT, item.getProviderLastUpdatedAt())
                .set(ITEMS.LAST_SYNC_AT, java.time.OffsetDateTime.now())
                .onConflict(ITEMS.CONNECTION_ID, ITEMS.EXTERNAL_ID)
                .doUpdate()
                .set(ITEMS.NAME, org.jooq.impl.DSL.excluded(ITEMS.NAME))
                .set(ITEMS.DESCRIPTION, org.jooq.impl.DSL.excluded(ITEMS.DESCRIPTION))
                .set(ITEMS.TYPE, org.jooq.impl.DSL.excluded(ITEMS.TYPE))
                .set(ITEMS.UNIT_PRICE, org.jooq.impl.DSL.excluded(ITEMS.UNIT_PRICE))
                .set(ITEMS.PURCHASE_COST, org.jooq.impl.DSL.excluded(ITEMS.PURCHASE_COST))
                .set(ITEMS.ACTIVE, org.jooq.impl.DSL.excluded(ITEMS.ACTIVE))
                .set(ITEMS.TAXABLE, org.jooq.impl.DSL.excluded(ITEMS.TAXABLE))
                .set(ITEMS.QUANTITY_ON_HAND, org.jooq.impl.DSL.excluded(ITEMS.QUANTITY_ON_HAND))
                .set(ITEMS.EXTERNAL_METADATA, org.jooq.impl.DSL.excluded(ITEMS.EXTERNAL_METADATA))
                .set(ITEMS.PROVIDER_LAST_UPDATED_AT, org.jooq.impl.DSL.excluded(ITEMS.PROVIDER_LAST_UPDATED_AT))
                .set(ITEMS.LAST_SYNC_AT, org.jooq.impl.DSL.excluded(ITEMS.LAST_SYNC_AT))
                .execute();
        }
        log.debug("Upserted {} items for connection: {}", items.size(), connectionId);
    }

    @Override
    public List<Item> findByCompanyId(Long companyId) {
        return dsl.selectFrom(ITEMS)
            .where(ITEMS.COMPANY_ID.eq(companyId))
            .fetch()
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public Item findById(String id) {
        return dsl.selectFrom(ITEMS)
            .where(ITEMS.ID.eq(id))
            .fetchOptional()
            .map(this::toDomain)
            .orElseThrow(() -> new NotFoundException(
                ApiErrorMessages.ITEM_NOT_FOUND_CODE,
                ApiErrorMessages.ITEM_NOT_FOUND
            ));
    }

    @Override
    public List<Item> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(ITEMS)
            .where(ITEMS.ID.in(ids))
            .fetch()
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    @SneakyThrows
    public Item create(Long companyId, Item item) {
        try {
            var record = dsl.insertInto(ITEMS)
                .set(ITEMS.COMPANY_ID, companyId)
                .set(ITEMS.NAME, item.getName())
                .set(ITEMS.CODE, item.getCode())
                .set(ITEMS.DESCRIPTION, item.getDescription())
                .set(ITEMS.TYPE, item.getType())
                .set(ITEMS.PURCHASE_COST, item.getPurchaseCost())
                .set(ITEMS.ACTIVE, true)
                .returning()
                .fetchSingle();
            
            log.debug("Created item: id={}, name={}", record.getId(), record.getName());
            return toDomain(record);
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(
                ApiErrorMessages.ITEM_ALREADY_EXISTS_CODE,
                ApiErrorMessages.ITEM_ALREADY_EXISTS
            );
        }
    }

    @Override
    @SneakyThrows
    public Item update(Item item) {
        var record = dsl.update(ITEMS)
            .set(ITEMS.NAME, item.getName())
            .set(ITEMS.CODE, item.getCode())
            .set(ITEMS.DESCRIPTION, item.getDescription())
            .set(ITEMS.TYPE, item.getType())
            .set(ITEMS.PURCHASE_COST, item.getPurchaseCost())
            .set(ITEMS.ACTIVE, item.getActive())
            .where(ITEMS.ID.eq(item.getId()))
            .returning()
            .fetchSingle();
        
        log.debug("Updated item: id={}, name={}", record.getId(), record.getName());
        return toDomain(record);
    }

    @Override
    public Map<String, String> findIdsByExternalIdsAndConnection(String connectionId, List<String> externalIds) {
        if (connectionId == null || externalIds == null || externalIds.isEmpty()) {
            return Map.of();
        }
        
        return dsl.select(ITEMS.EXTERNAL_ID, ITEMS.ID)
            .from(ITEMS)
            .where(ITEMS.CONNECTION_ID.eq(connectionId))
            .and(ITEMS.EXTERNAL_ID.in(externalIds))
            .fetchMap(ITEMS.EXTERNAL_ID, ITEMS.ID);
    }

    @Override
    public void updateSyncStatus(String itemId, String provider, String externalId, String providerVersion, OffsetDateTime providerLastUpdatedAt) {
        dsl.update(ITEMS)
                .set(ITEMS.PROVIDER, provider)
                .set(ITEMS.EXTERNAL_ID, externalId)
                .set(ITEMS.PROVIDER_VERSION, providerVersion)
                .set(ITEMS.PROVIDER_LAST_UPDATED_AT, providerLastUpdatedAt)
                .set(ITEMS.LAST_SYNC_AT, OffsetDateTime.now()) // Track when we successfully pushed to provider
                // Reset retry tracking on successful push
                .set(ITEMS.PUSH_RETRY_COUNT, 0)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, false)
                .set(ITEMS.PUSH_FAILURE_REASON, (String) null)
                // DO NOT set updated_at - it represents last local modification, not sync time
                .where(ITEMS.ID.eq(itemId))
                .execute();
    }

    @Override
    public void batchUpdateSyncStatus(List<SyncStatusUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();

        // Build batch update queries
        var queries = updates.stream()
            .map(update -> dsl.update(ITEMS)
                .set(ITEMS.PROVIDER, update.provider())
                .set(ITEMS.EXTERNAL_ID, update.externalId())
                .set(ITEMS.PROVIDER_VERSION, update.providerVersion())
                .set(ITEMS.PROVIDER_LAST_UPDATED_AT, update.providerLastUpdatedAt())
                .set(ITEMS.LAST_SYNC_AT, now)
                .where(ITEMS.ID.eq(update.id())))
            .toList();

        // Execute as batch for performance
        int[] results = dsl.batch(queries).execute();

        log.debug("Batch updated sync status for {} items", results.length);
    }

    @Override
    public List<Item> findNeedingPush(Long companyId, String connectionId, int limit, int maxRetries) {
        return dsl.selectFrom(ITEMS)
            .where(ITEMS.COMPANY_ID.eq(companyId))
            .and(ITEMS.CONNECTION_ID.eq(connectionId))
            // Items are pulled from QuickBooks and don't have local modifications (no updated_at column)
            // We only push items that have never been synced (last_sync_at IS NULL)
            // Once pushed successfully, last_sync_at is set and they won't be pushed again
            .and(ITEMS.LAST_SYNC_AT.isNull())
            // Exclude permanently failed and over max retries
            .and(ITEMS.PUSH_PERMANENTLY_FAILED.eq(false))
            .and(ITEMS.PUSH_RETRY_COUNT.lessThan(maxRetries))
            .limit(limit)
            .fetch()
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public void clearSyncStatus(String itemId) {
        dsl.update(ITEMS)
                .setNull(ITEMS.PROVIDER_LAST_UPDATED_AT)
                .where(ITEMS.ID.eq(itemId))
                .execute();
        log.debug("Cleared sync status for item: {}", itemId);
    }

    private Item toDomain(com.tosspaper.models.jooq.tables.records.ItemsRecord record) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (record.getExternalMetadata() != null) {
                try {
                    metadata = objectMapper.readValue(record.getExternalMetadata().data(), new TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse metadata for item: id={}", record.getId(), e);
                }
            }

            Item item = Item.builder()
                .id(record.getId())
                .companyId(record.getCompanyId())
                .connectionId(record.getConnectionId())
                .name(record.getName())
                .code(record.getCode())
                .description(record.getDescription())
                .type(record.getType())
                .unitPrice(record.getUnitPrice())
                .purchaseCost(record.getPurchaseCost())
                .active(record.getActive())
                .taxable(record.getTaxable())
                .quantityOnHand(record.getQuantityOnHand())
                .createdAt(record.getCreatedAt())
                .build();

            // Set inherited ProviderTracked fields via setters (not in builder)
            item.setExternalId(record.getExternalId());
            item.setExternalMetadata(metadata);
            item.setProviderCreatedAt(record.getProviderCreatedAt());
            item.setProviderLastUpdatedAt(record.getProviderLastUpdatedAt());

            // Set retry tracking fields
            item.setPushRetryCount(record.getPushRetryCount());
            item.setPushRetryLastAttemptAt(record.getPushRetryLastAttemptAt());
            item.setPushPermanentlyFailed(record.getPushPermanentlyFailed());
            item.setPushFailureReason(record.getPushFailureReason());

            return item;
        } catch (Exception e) {
            log.error("Failed to convert record to domain: id={}", record.getId(), e);
            throw new RuntimeException("Failed to convert record to domain", e);
        }
    }

    @Override
    public void incrementRetryCount(String itemId, String errorMessage) {
        dsl.update(ITEMS)
                .set(ITEMS.PUSH_RETRY_COUNT, ITEMS.PUSH_RETRY_COUNT.add(1))
                .set(ITEMS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .set(ITEMS.PUSH_FAILURE_REASON, errorMessage)
                .where(ITEMS.ID.eq(itemId))
                .execute();
        log.debug("Incremented retry count for item: {}", itemId);
    }

    @Override
    public void markAsPermanentlyFailed(String itemId, String errorMessage) {
        dsl.update(ITEMS)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, true)
                .set(ITEMS.PUSH_FAILURE_REASON, errorMessage)
                .set(ITEMS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .where(ITEMS.ID.eq(itemId))
                .execute();
        log.warn("Marked item {} as permanently failed: {}", itemId, errorMessage);
    }

    @Override
    public void resetRetryTracking(String itemId) {
        dsl.update(ITEMS)
                .set(ITEMS.PUSH_RETRY_COUNT, 0)
                .set(ITEMS.PUSH_PERMANENTLY_FAILED, false)
                .set(ITEMS.PUSH_FAILURE_REASON, (String) null)
                .set(ITEMS.PUSH_RETRY_LAST_ATTEMPT_AT, (OffsetDateTime) null)
                .where(ITEMS.ID.eq(itemId))
                .execute();
        log.debug("Reset retry tracking for item: {}", itemId);
    }
}
