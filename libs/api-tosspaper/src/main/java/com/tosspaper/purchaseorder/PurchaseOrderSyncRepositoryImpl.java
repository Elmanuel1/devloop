package com.tosspaper.purchaseorder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.PurchaseOrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDERS;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDER_ITEMS;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDER_FLAT_ITEMS;
import static org.jooq.impl.DSL.*;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PurchaseOrderSyncRepositoryImpl implements PurchaseOrderSyncRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    @SneakyThrows
    public void upsertFromProvider(Long companyId, List<PurchaseOrder> purchaseOrders) {
        if (purchaseOrders.isEmpty()) {
            return;
        }

        dsl.transaction(configuration -> {
            var ctx = configuration.dsl();

            // Process each PO individually to handle items properly
            // Uses CTE pattern: WITH updated AS (UPDATE ... RETURNING) INSERT ... WHERE NOT
            // EXISTS
            // to handle conflicts on two unique constraints:
            // 1. (company_id, provider, external_id)
            // 2. (company_id, display_id)
            for (PurchaseOrder po : purchaseOrders) {
                String provider = po.getProvider() != null ? po.getProvider().toLowerCase() : null;
                String externalId = po.getExternalId();
                JSONB externalMetadataJson = mapToJsonb(po.getExternalMetadata());
                JSONB metadataJson = mapToJsonb(po.getMetadata());
                String displayId = po.getDisplayId();
                JSONB vendorContactJson = objectToJsonb(po.getVendorContact());
                JSONB shipToContactJson = objectToJsonb(po.getShipToContact());
                java.time.LocalDate orderDate = po.getOrderDate() != null ? po.getOrderDate() : null;
                java.time.LocalDate dueDate = po.getDueDate() != null ? po.getDueDate() : null;
                String status = po.getStatus() != null ? po.getStatus().getValue() : null;
                String currencyCode = po.getCurrencyCode() != null ? po.getCurrencyCode().getCode() : null;
                String notes = po.getNotes();
                OffsetDateTime providerCreatedAt = po.getProviderCreatedAt();
                OffsetDateTime providerLastUpdatedAt = po.getProviderLastUpdatedAt();

                var updated = name("updated");

                // CTE-based upsert to handle multiple unique constraints
                var upsertQuery = ctx.with(updated)
                        .as(ctx.update(PURCHASE_ORDERS)
                                .set(PURCHASE_ORDERS.PROVIDER, provider)
                                .set(PURCHASE_ORDERS.EXTERNAL_ID, externalId)
                                .set(PURCHASE_ORDERS.DISPLAY_ID, displayId)
                                .set(PURCHASE_ORDERS.ORDER_DATE, orderDate)
                                .set(PURCHASE_ORDERS.DUE_DATE, dueDate)
                                .set(PURCHASE_ORDERS.VENDOR_CONTACT, vendorContactJson)
                                .set(PURCHASE_ORDERS.SHIP_TO_CONTACT, shipToContactJson)
                                .set(PURCHASE_ORDERS.STATUS, status)
                                .set(PURCHASE_ORDERS.CURRENCY_CODE, currencyCode)
                                .set(PURCHASE_ORDERS.NOTES, notes)
                                .set(PURCHASE_ORDERS.METADATA, metadataJson)
                                .set(PURCHASE_ORDERS.EXTERNAL_METADATA, externalMetadataJson)
                                .set(PURCHASE_ORDERS.PROVIDER_VERSION, po.getProviderVersion())
                                .set(PURCHASE_ORDERS.PROVIDER_CREATED_AT, providerCreatedAt)
                                .set(PURCHASE_ORDERS.PROVIDER_LAST_UPDATED_AT, providerLastUpdatedAt)
                                .set(PURCHASE_ORDERS.DELETED_AT, po.getDeletedAt())
                                .where(
                                        // First condition: (company_id, provider, external_id) - same provider record
                                        PURCHASE_ORDERS.COMPANY_ID.eq(companyId)
                                                .and(PURCHASE_ORDERS.PROVIDER.eq(provider))
                                                .and(PURCHASE_ORDERS.EXTERNAL_ID.eq(externalId))
                                                // OR second condition: (company_id, display_id) - any record including
                                                // local
                                                .or(PURCHASE_ORDERS.COMPANY_ID.eq(companyId)
                                                        .and(PURCHASE_ORDERS.DISPLAY_ID.eq(displayId))))
                                .returning(PURCHASE_ORDERS.ID))
                        .insertInto(PURCHASE_ORDERS,
                                PURCHASE_ORDERS.COMPANY_ID,
                                PURCHASE_ORDERS.PROVIDER,
                                PURCHASE_ORDERS.EXTERNAL_ID,
                                PURCHASE_ORDERS.DISPLAY_ID,
                                PURCHASE_ORDERS.ORDER_DATE,
                                PURCHASE_ORDERS.DUE_DATE,
                                PURCHASE_ORDERS.VENDOR_CONTACT,
                                PURCHASE_ORDERS.SHIP_TO_CONTACT,
                                PURCHASE_ORDERS.STATUS,
                                PURCHASE_ORDERS.CURRENCY_CODE,
                                PURCHASE_ORDERS.NOTES,
                                PURCHASE_ORDERS.METADATA,
                                PURCHASE_ORDERS.EXTERNAL_METADATA,
                                PURCHASE_ORDERS.PROVIDER_VERSION,
                                PURCHASE_ORDERS.PROVIDER_CREATED_AT,
                                PURCHASE_ORDERS.PROVIDER_LAST_UPDATED_AT,
                                PURCHASE_ORDERS.CREATED_AT,
                                PURCHASE_ORDERS.DELETED_AT)
                        .select(
                                select(
                                        val(companyId),
                                        val(provider),
                                        val(externalId),
                                        val(displayId),
                                        val(orderDate),
                                        val(dueDate),
                                        val(vendorContactJson),
                                        val(shipToContactJson),
                                        val(status),
                                        val(currencyCode),
                                        val(notes),
                                        val(metadataJson),
                                        val(externalMetadataJson),
                                        val(po.getProviderVersion()),
                                        val(providerCreatedAt),
                                        val(providerLastUpdatedAt),
                                        val(providerCreatedAt), // created_at
                                        val(po.getDeletedAt()))
                                        .whereNotExists(selectOne().from(updated)))
                        .returning(PURCHASE_ORDERS.ID);

                // Execute and get ID (from either UPDATE or INSERT)
                var result = upsertQuery.fetch();
                String poId;
                if (!result.isEmpty()) {
                    poId = result.getFirst().get(PURCHASE_ORDERS.ID);
                } else {
                    // If INSERT returned nothing (row was updated), fetch the ID
                    poId = ctx.select(PURCHASE_ORDERS.ID)
                            .from(PURCHASE_ORDERS)
                            .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId)
                                    .and(PURCHASE_ORDERS.PROVIDER.eq(provider))
                                    .and(PURCHASE_ORDERS.EXTERNAL_ID.eq(externalId)))
                            .fetchOne(PURCHASE_ORDERS.ID);
                }

                // Handle line items
                if (poId != null && po.getItems() != null && !po.getItems().isEmpty()) {
                    // Delete existing items for this PO
                    ctx.deleteFrom(PURCHASE_ORDER_ITEMS)
                            .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(poId))
                            .execute();

                    // Insert new items
                    var itemInserts = po.getItems().stream()
                            .map(item -> ctx.insertInto(PURCHASE_ORDER_ITEMS)
                                    .set(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID, poId)
                                    .set(PURCHASE_ORDER_ITEMS.ITEM_ID, item.getItemId())
                                    .set(PURCHASE_ORDER_ITEMS.ACCOUNT_ID, item.getAccountId())
                                    .set(PURCHASE_ORDER_ITEMS.EXTERNAL_ITEM_ID, item.getExternalItemId())
                                    .set(PURCHASE_ORDER_ITEMS.EXTERNAL_ACCOUNT_ID, item.getExternalAccountId())
                                    .set(PURCHASE_ORDER_ITEMS.NAME, item.getName() != null ? item.getName() : "")
                                    .set(PURCHASE_ORDER_ITEMS.QUANTITY,
                                            item.getQuantity() != null ? item.getQuantity() : 0)
                                    .set(PURCHASE_ORDER_ITEMS.UNIT, item.getUnit())
                                    .set(PURCHASE_ORDER_ITEMS.UNIT_CODE, item.getUnitCode())
                                    .set(PURCHASE_ORDER_ITEMS.UNIT_PRICE, item.getUnitPrice())
                                    .set(PURCHASE_ORDER_ITEMS.EXPECTED_DELIVERY_DATE, item.getExpectedDeliveryDate())
                                    .set(PURCHASE_ORDER_ITEMS.DELIVERY_STATUS, item.getDeliveryStatus())
                                    .set(PURCHASE_ORDER_ITEMS.NOTES, item.getNotes())
                                    .set(PURCHASE_ORDER_ITEMS.METADATA, objectToJsonb(item.getMetadata())))
                            .toList();

                    ctx.batch(itemInserts).execute();
                }
            }

            log.debug("Upsert completed for {} purchase orders with items", purchaseOrders.size());
        });
    }

    private JSONB mapToJsonb(java.util.Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        try {
            return JSONB.jsonbOrNull(objectMapper.writeValueAsString(map));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to serialize map to JSONB", e);
            return null;
        }
    }

    private JSONB objectToJsonb(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return JSONB.jsonbOrNull(objectMapper.writeValueAsString(obj));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to serialize object to JSONB", e);
            return null;
        }
    }

    @Override
    public int deleteByProviderAndExternalIds(Long companyId, String provider, List<String> externalIds) {
        if (externalIds == null || externalIds.isEmpty()) {
            return 0;
        }

        String providerLower = provider != null ? provider.toLowerCase() : null;
        OffsetDateTime now = OffsetDateTime.now();

        // Soft delete purchase orders (set DELETED_AT)
        int deleted = dsl.update(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.DELETED_AT, now)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.PROVIDER.eq(providerLower))
                .and(PURCHASE_ORDERS.EXTERNAL_ID.in(externalIds))
                .and(PURCHASE_ORDERS.DELETED_AT.isNull()) // Only soft delete if not already deleted
                .execute();

        log.info("Soft deleted {} purchase orders for companyId: {}, provider: {}",
                deleted, companyId, provider);
        return deleted;
    }

    @Override
    public void updateSyncStatus(String poId, String externalId, String providerVersion,
            OffsetDateTime providerLastUpdatedAt) {
        int updated = dsl.update(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.EXTERNAL_ID, externalId)
                .set(PURCHASE_ORDERS.PROVIDER_VERSION, providerVersion)
                .set(PURCHASE_ORDERS.PROVIDER_LAST_UPDATED_AT, providerLastUpdatedAt)
                .set(PURCHASE_ORDERS.LAST_SYNC_AT, OffsetDateTime.now()) // Track when we successfully pushed to
                                                                         // provider
                // NEW: Reset retry tracking on successful push
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .set(PURCHASE_ORDERS.PUSH_FAILURE_REASON, (String) null)
                // DO NOT set UPDATED_AT - it represents last local modification, not sync time
                .where(PURCHASE_ORDERS.ID.eq(poId))
                .execute();

        if (updated == 0) {
            log.warn("No purchase order found with id {} to update sync status", poId);
        } else {
            log.debug("Updated sync status for purchase order {}: externalId={}, providerVersion={}",
                    poId, externalId, providerVersion);
        }
    }

    @Override
    public List<PurchaseOrder> findNeedingPush(Long companyId, int limit, int maxRetries) {
        // Step 1: Query POs needing push
        var poRecords = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                // Condition 1: Has local changes that haven't been pushed yet
                .and(PURCHASE_ORDERS.UPDATED_AT.greaterThan(PURCHASE_ORDERS.LAST_SYNC_AT)
                        .or(PURCHASE_ORDERS.LAST_SYNC_AT.isNull()))
                // Condition 2: Our local changes are newer than the provider's version
                .and(PURCHASE_ORDERS.UPDATED_AT.greaterThan(PURCHASE_ORDERS.PROVIDER_LAST_UPDATED_AT)
                        .or(PURCHASE_ORDERS.PROVIDER_LAST_UPDATED_AT.isNull()))
                .and(PURCHASE_ORDERS.DELETED_AT.isNull())
                // Condition 3: Exclude POs with line items that have neither itemId nor
                // accountId
                .andNotExists(
                        dsl.selectOne()
                                .from(PURCHASE_ORDER_ITEMS)
                                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(PURCHASE_ORDERS.ID))
                                .and(PURCHASE_ORDER_ITEMS.ITEM_ID.isNull())
                                .and(PURCHASE_ORDER_ITEMS.ACCOUNT_ID.isNull()))
                // NEW: Exclude permanently failed and over max retries
                .and(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED.eq(false))
                .and(PURCHASE_ORDERS.PUSH_RETRY_COUNT.lessThan(maxRetries))
                .limit(limit)
                .fetch();

        if (poRecords.isEmpty()) {
            log.info("No purchase orders needing push for company {}", companyId);
            return List.of();
        }

        // Step 2: Batch load line items for all POs
        var poIds = poRecords.stream().map(r -> r.getId()).toList();
        var lineItemRecords = dsl.selectFrom(PURCHASE_ORDER_ITEMS)
                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.in(poIds))
                .orderBy(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.asc())
                .fetch();

        // Group line items by PO ID
        var lineItemsByPoId = new java.util.HashMap<String, java.util.List<com.tosspaper.models.domain.PurchaseOrderItem>>();
        for (var itemRecord : lineItemRecords) {
            String poId = itemRecord.getPurchaseOrderId();

            com.tosspaper.models.domain.PurchaseOrderItem item = new com.tosspaper.models.domain.PurchaseOrderItem();
            item.setItemId(itemRecord.getItemId());
            item.setAccountId(itemRecord.getAccountId());
            item.setExternalItemId(itemRecord.getExternalItemId());
            item.setExternalAccountId(itemRecord.getExternalAccountId());
            item.setName(itemRecord.getName());
            item.setQuantity(itemRecord.getQuantity());
            item.setUnit(itemRecord.getUnit());
            item.setUnitCode(itemRecord.getUnitCode());
            item.setUnitPrice(itemRecord.getUnitPrice());
            item.setExpectedDeliveryDate(itemRecord.getExpectedDeliveryDate());
            item.setDeliveryStatus(itemRecord.getDeliveryStatus());
            item.setNotes(itemRecord.getNotes());

            // Parse line item metadata
            if (itemRecord.getMetadata() != null) {
                try {
                    java.util.Map<String, Object> itemMetadata = objectMapper.readValue(
                            itemRecord.getMetadata().data(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                            });
                    item.setMetadata(itemMetadata);
                } catch (Exception e) {
                    log.warn("Failed to parse line item metadata for PO {}", poId, e);
                }
            }

            lineItemsByPoId.computeIfAbsent(poId, k -> new ArrayList<>()).add(item);
        }

        // Step 3: Build complete PurchaseOrder objects
        List<PurchaseOrder> result = new ArrayList<>();
        for (var poRecord : poRecords) {
            PurchaseOrder po = new PurchaseOrder();
            po.setId(poRecord.getId());
            po.setDisplayId(poRecord.getDisplayId());
            po.setCompanyId(poRecord.getCompanyId());
            po.setProjectId(poRecord.getProjectId());
            po.setOrderDate(poRecord.getOrderDate());
            po.setDueDate(poRecord.getDueDate());
            po.setNotes(poRecord.getNotes());
            po.setProvider(poRecord.getProvider());
            po.setExternalId(poRecord.getExternalId());
            po.setProviderVersion(poRecord.getProviderVersion());
            po.setProviderCreatedAt(poRecord.getProviderCreatedAt());
            po.setProviderLastUpdatedAt(poRecord.getProviderLastUpdatedAt());
            po.setCreatedAt(poRecord.getCreatedAt());
            po.setUpdatedAt(poRecord.getUpdatedAt());

            // Set retry tracking fields
            po.setPushRetryCount(poRecord.getPushRetryCount());
            po.setPushRetryLastAttemptAt(poRecord.getPushRetryLastAttemptAt());
            po.setPushPermanentlyFailed(poRecord.getPushPermanentlyFailed());
            po.setPushFailureReason(poRecord.getPushFailureReason());

            // Parse status
            if (poRecord.getStatus() != null) {
                try {
                    po.setStatus(PurchaseOrderStatus.fromValue(poRecord.getStatus()));
                } catch (Exception e) {
                    log.warn("Failed to parse status for PO {}: {}", poRecord.getId(), poRecord.getStatus());
                }
            }

            // Parse external metadata
            if (poRecord.getExternalMetadata() != null) {
                try {
                    java.util.Map<String, Object> metadata = objectMapper.readValue(
                            poRecord.getExternalMetadata().data(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                            });
                    po.setExternalMetadata(metadata);
                } catch (Exception e) {
                    log.warn("Failed to parse external metadata for PO {}", poRecord.getId(), e);
                }
            }

            // Parse metadata
            if (poRecord.getMetadata() != null) {
                try {
                    java.util.Map<String, Object> metadata = objectMapper.readValue(
                            poRecord.getMetadata().data(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                            });
                    po.setMetadata(metadata);
                } catch (Exception e) {
                    log.warn("Failed to parse metadata for PO {}", poRecord.getId(), e);
                }
            }

            // Parse vendor contact from JSONB
            if (poRecord.getVendorContact() != null)

            {
                try {
                    com.tosspaper.models.domain.Party vendor = objectMapper.readValue(
                            poRecord.getVendorContact().data(),
                            com.tosspaper.models.domain.Party.class);
                    po.setVendorContact(vendor);
                } catch (Exception e) {
                    log.warn("Failed to parse vendor contact for PO {}", poRecord.getId(), e);
                }
            }

            // Parse ship-to contact from JSONB
            if (poRecord.getShipToContact() != null) {
                try {
                    com.tosspaper.models.domain.Party shipTo = objectMapper.readValue(
                            poRecord.getShipToContact().data(),
                            com.tosspaper.models.domain.Party.class);
                    po.setShipToContact(shipTo);
                } catch (Exception e) {
                    log.warn("Failed to parse ship-to contact for PO {}", poRecord.getId(), e);
                }
            }

            // Attach line items
            po.setItems(lineItemsByPoId.getOrDefault(poRecord.getId(), new ArrayList<>()));

            result.add(po);
        }

        log.info("Found {} purchase orders needing push for company {}", result.size(), companyId);
        return result;
    }

    @Override
    public PurchaseOrder findByProviderAndExternalId(Long companyId, String provider, String externalId) {
        String providerLower = provider != null ? provider.toLowerCase() : null;

        // 1. Fetch Purchase Order
        var poRecord = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.PROVIDER.eq(providerLower))
                .and(PURCHASE_ORDERS.EXTERNAL_ID.eq(externalId))
                .and(PURCHASE_ORDERS.DELETED_AT.isNull())
                .fetchOne();

        if (poRecord == null) {
            return null;
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setId(poRecord.getId());
        po.setDisplayId(poRecord.getDisplayId());
        po.setCompanyId(poRecord.getCompanyId());
        po.setProjectId(poRecord.getProjectId());
        po.setOrderDate(poRecord.getOrderDate());
        po.setDueDate(poRecord.getDueDate());
        po.setNotes(poRecord.getNotes());
        po.setProvider(poRecord.getProvider());
        po.setExternalId(poRecord.getExternalId());
        po.setProviderVersion(poRecord.getProviderVersion());
        po.setProviderLastUpdatedAt(poRecord.getProviderLastUpdatedAt());
        po.setCreatedAt(poRecord.getCreatedAt());
        po.setUpdatedAt(poRecord.getUpdatedAt());

        // Parse ship-to contact from JSONB
        if (poRecord.getShipToContact() != null) {
            try {
                com.tosspaper.models.domain.Party shipTo = objectMapper.readValue(
                        poRecord.getShipToContact().data(),
                        com.tosspaper.models.domain.Party.class);
                po.setShipToContact(shipTo);
            } catch (Exception e) {
                log.warn("Failed to parse ship-to contact for PO {}", po.getId(), e);
            }
        }

        // Parse vendor contact from JSONB
        if (poRecord.getVendorContact() != null) {
            try {
                com.tosspaper.models.domain.Party vendor = objectMapper.readValue(
                        poRecord.getVendorContact().data(),
                        com.tosspaper.models.domain.Party.class);
                po.setVendorContact(vendor);
            } catch (Exception e) {
                log.warn("Failed to parse vendor contact for PO {}", po.getId(), e);
            }
        }

        // 2. Fetch Line Items
        var itemRecords = dsl.selectFrom(PURCHASE_ORDER_ITEMS)
                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(po.getId()))
                .orderBy(PURCHASE_ORDER_ITEMS.ID.asc())
                .fetch();

        po.setItems(new ArrayList<>());
        for (var itemRecord : itemRecords) {
            com.tosspaper.models.domain.PurchaseOrderItem item = new com.tosspaper.models.domain.PurchaseOrderItem();
            item.setId(itemRecord.getId());
            item.setItemId(itemRecord.getItemId());
            item.setAccountId(itemRecord.getAccountId());
            item.setExternalItemId(itemRecord.getExternalItemId());
            item.setExternalAccountId(itemRecord.getExternalAccountId());
            item.setName(itemRecord.getName());
            item.setQuantity(itemRecord.getQuantity());
            item.setUnit(itemRecord.getUnit());
            item.setUnitCode(itemRecord.getUnitCode());
            item.setUnitPrice(itemRecord.getUnitPrice());
            item.setExpectedDeliveryDate(itemRecord.getExpectedDeliveryDate());
            item.setDeliveryStatus(itemRecord.getDeliveryStatus());
            item.setNotes(itemRecord.getNotes());

            // Parse line item metadata
            if (itemRecord.getMetadata() != null) {
                try {
                    java.util.Map<String, Object> itemMetadata = objectMapper.readValue(
                            itemRecord.getMetadata().data(),
                            new TypeReference<java.util.Map<String, Object>>() {
                            });
                    item.setMetadata(itemMetadata);
                } catch (Exception e) {
                    log.warn("Failed to parse line item metadata for PO item {}", itemRecord.getId(), e);
                }
            }

            po.getItems().add(item);
        }

        return po;
    }

    @Override
    public List<PurchaseOrder> findByCompanyIdAndDisplayIds(Long companyId, List<String> displayIds) {
        if (displayIds == null || displayIds.isEmpty()) {
            return List.of();
        }

        // 1. Fetch Purchase Orders
        var poRecords = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.DISPLAY_ID.in(displayIds))
                .and(PURCHASE_ORDERS.DELETED_AT.isNull())
                .orderBy(PURCHASE_ORDERS.ID.asc())
                .fetch();

        if (poRecords.isEmpty()) {
            return List.of();
        }

        List<String> poIds = poRecords.map(r -> r.get(PURCHASE_ORDERS.ID));
        Map<String, PurchaseOrder> poMap = new java.util.LinkedHashMap<>();

        for (var poRecord : poRecords) {
            String poId = poRecord.getId();
            PurchaseOrder po = new PurchaseOrder();
            po.setId(poId);
            po.setDisplayId(poRecord.getDisplayId());
            po.setCompanyId(poRecord.getCompanyId());
            po.setProjectId(poRecord.getProjectId());
            po.setOrderDate(poRecord.getOrderDate());
            po.setDueDate(poRecord.getDueDate());
            po.setNotes(poRecord.getNotes());
            po.setProvider(poRecord.getProvider());
            po.setExternalId(poRecord.getExternalId());
            po.setProviderVersion(poRecord.getProviderVersion());
            po.setProviderLastUpdatedAt(poRecord.getProviderLastUpdatedAt());
            po.setCreatedAt(poRecord.getCreatedAt());
            po.setUpdatedAt(poRecord.getUpdatedAt());
            po.setItems(new ArrayList<>());

            // Parse vendor contact from JSONB
            if (poRecord.getVendorContact() != null) {
                try {
                    com.tosspaper.models.domain.Party vendor = objectMapper.readValue(
                            poRecord.getVendorContact().data(),
                            com.tosspaper.models.domain.Party.class);
                    po.setVendorContact(vendor);
                } catch (Exception e) {
                    log.warn("Failed to parse vendor contact for PO {}", poId, e);
                }
            }

            // Parse ship-to contact from JSONB
            if (poRecord.getShipToContact() != null) {
                try {
                    com.tosspaper.models.domain.Party shipTo = objectMapper.readValue(
                            poRecord.getShipToContact().data(),
                            com.tosspaper.models.domain.Party.class);
                    po.setShipToContact(shipTo);
                } catch (Exception e) {
                    log.warn("Failed to parse ship-to contact for PO {}", poId, e);
                }
            }

            poMap.put(poId, po);
        }

        // 2. Fetch Line Items for all found POs
        var itemRecords = dsl.selectFrom(PURCHASE_ORDER_ITEMS)
                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.in(poIds))
                .orderBy(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.asc(), PURCHASE_ORDER_ITEMS.ID.asc())
                .fetch();

        // 3. Attach items to POs
        for (var itemRecord : itemRecords) {
            String poId = itemRecord.getPurchaseOrderId();
            PurchaseOrder po = poMap.get(poId);
            if (po != null) {
                com.tosspaper.models.domain.PurchaseOrderItem item = new com.tosspaper.models.domain.PurchaseOrderItem();
                item.setId(itemRecord.getId());
                item.setItemId(itemRecord.getItemId());
                item.setAccountId(itemRecord.getAccountId());
                item.setExternalItemId(itemRecord.getExternalItemId());
                item.setExternalAccountId(itemRecord.getExternalAccountId());
                item.setName(itemRecord.getName());
                item.setQuantity(itemRecord.getQuantity());
                item.setUnit(itemRecord.getUnit());
                item.setUnitCode(itemRecord.getUnitCode());
                item.setUnitPrice(itemRecord.getUnitPrice());
                item.setExpectedDeliveryDate(itemRecord.getExpectedDeliveryDate());
                item.setDeliveryStatus(itemRecord.getDeliveryStatus());
                item.setNotes(itemRecord.getNotes());

                // Parse line item metadata
                if (itemRecord.getMetadata() != null) {
                    try {
                        java.util.Map<String, Object> itemMetadata = objectMapper.readValue(
                                itemRecord.getMetadata().data(),
                                new TypeReference<java.util.Map<String, Object>>() {
                                });
                        item.setMetadata(itemMetadata);
                    } catch (Exception e) {
                        log.warn("Failed to parse line item metadata for PO item {}", itemRecord.getId(), e);
                    }
                }

                po.getItems().add(item);
            }
        }

        return new ArrayList<>(poMap.values());
    }

    @Override
    public PurchaseOrder findById(String poId) {
        // 1. Fetch Purchase Order
        var poRecord = dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.ID.eq(poId))
                .fetchOne();

        if (poRecord == null) {
            return null;
        }

        PurchaseOrder po = new PurchaseOrder();
        po.setId(poRecord.getId());
        po.setDisplayId(poRecord.getDisplayId());
        po.setCompanyId(poRecord.getCompanyId());
        po.setProjectId(poRecord.getProjectId());
        po.setOrderDate(poRecord.getOrderDate());
        po.setDueDate(poRecord.getDueDate());
        po.setNotes(poRecord.getNotes());
        po.setProvider(poRecord.getProvider());
        po.setExternalId(poRecord.getExternalId());
        po.setProviderVersion(poRecord.getProviderVersion());
        po.setProviderLastUpdatedAt(poRecord.getProviderLastUpdatedAt());
        po.setCreatedAt(poRecord.getCreatedAt());
        po.setUpdatedAt(poRecord.getUpdatedAt());

        // Set retry tracking fields
        po.setPushRetryCount(poRecord.getPushRetryCount());
        po.setPushRetryLastAttemptAt(poRecord.getPushRetryLastAttemptAt());
        po.setPushPermanentlyFailed(poRecord.getPushPermanentlyFailed());
        po.setPushFailureReason(poRecord.getPushFailureReason());

        // Parse ship-to contact from JSONB
        if (poRecord.getShipToContact() != null) {
            try {
                com.tosspaper.models.domain.Party shipTo = objectMapper.readValue(
                        poRecord.getShipToContact().data(),
                        com.tosspaper.models.domain.Party.class);
                po.setShipToContact(shipTo);
            } catch (Exception e) {
                log.warn("Failed to parse ship-to contact for PO {}", poId, e);
            }
        }

        // Parse vendor contact from JSONB
        if (poRecord.getVendorContact() != null) {
            try {
                com.tosspaper.models.domain.Party vendor = objectMapper.readValue(
                        poRecord.getVendorContact().data(),
                        com.tosspaper.models.domain.Party.class);
                po.setVendorContact(vendor);
            } catch (Exception e) {
                log.warn("Failed to parse vendor contact for PO {}", poId, e);
            }
        }

        // 2. Fetch Line Items
        var itemRecords = dsl.selectFrom(PURCHASE_ORDER_ITEMS)
                .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(poId))
                .orderBy(PURCHASE_ORDER_ITEMS.ID.asc())
                .fetch();

        po.setItems(new ArrayList<>());
        for (var itemRecord : itemRecords) {
            com.tosspaper.models.domain.PurchaseOrderItem item = new com.tosspaper.models.domain.PurchaseOrderItem();
            item.setId(itemRecord.getId());
            item.setItemId(itemRecord.getItemId());
            item.setAccountId(itemRecord.getAccountId());
            item.setExternalItemId(itemRecord.getExternalItemId());
            item.setExternalAccountId(itemRecord.getExternalAccountId());
            item.setName(itemRecord.getName());
            item.setQuantity(itemRecord.getQuantity());
            item.setUnit(itemRecord.getUnit());
            item.setUnitCode(itemRecord.getUnitCode());
            item.setUnitPrice(itemRecord.getUnitPrice());
            item.setExpectedDeliveryDate(itemRecord.getExpectedDeliveryDate());
            item.setDeliveryStatus(itemRecord.getDeliveryStatus());
            item.setNotes(itemRecord.getNotes());

            // Parse line item metadata
            if (itemRecord.getMetadata() != null) {
                try {
                    java.util.Map<String, Object> itemMetadata = objectMapper.readValue(
                            itemRecord.getMetadata().data(),
                            new TypeReference<java.util.Map<String, Object>>() {
                            });
                    item.setMetadata(itemMetadata);
                } catch (Exception e) {
                    log.warn("Failed to parse line item metadata for PO item {}", itemRecord.getId(), e);
                }
            }

            po.getItems().add(item);
        }

        return po;
    }

    @Override
    public void incrementRetryCount(String poId, String errorMessage) {
        dsl.update(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, PURCHASE_ORDERS.PUSH_RETRY_COUNT.add(1))
                .set(PURCHASE_ORDERS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .set(PURCHASE_ORDERS.PUSH_FAILURE_REASON, errorMessage)
                .where(PURCHASE_ORDERS.ID.eq(poId))
                .execute();
        log.debug("Incremented retry count for purchase order: {}", poId);
    }

    @Override
    public void markAsPermanentlyFailed(String poId, String errorMessage) {
        dsl.update(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, true)
                .set(PURCHASE_ORDERS.PUSH_FAILURE_REASON, errorMessage)
                .set(PURCHASE_ORDERS.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .where(PURCHASE_ORDERS.ID.eq(poId))
                .execute();
        log.warn("Marked purchase order {} as permanently failed: {}", poId, errorMessage);
    }

    @Override
    public void resetRetryTracking(String poId) {
        dsl.update(PURCHASE_ORDERS)
                .set(PURCHASE_ORDERS.PUSH_RETRY_COUNT, 0)
                .set(PURCHASE_ORDERS.PUSH_PERMANENTLY_FAILED, false)
                .set(PURCHASE_ORDERS.PUSH_FAILURE_REASON, (String) null)
                .set(PURCHASE_ORDERS.PUSH_RETRY_LAST_ATTEMPT_AT, (OffsetDateTime) null)
                .where(PURCHASE_ORDERS.ID.eq(poId))
                .execute();
        log.debug("Reset retry tracking for purchase order: {}", poId);
    }
}
