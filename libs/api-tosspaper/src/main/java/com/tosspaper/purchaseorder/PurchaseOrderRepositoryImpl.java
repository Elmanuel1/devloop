package com.tosspaper.purchaseorder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.jooq.tables.pojos.PurchaseOrderItems;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.DuplicateException;
import com.tosspaper.models.jooq.tables.records.PurchaseOrderFlatItemsRecord;
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord;
import com.tosspaper.purchaseorder.model.ChangeLogEntry;
import com.tosspaper.purchaseorder.model.PurchaseOrderQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import com.tosspaper.common.query.QueryConditionBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.COMPANIES;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDERS;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDER_ITEMS;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDER_FLAT_ITEMS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PurchaseOrderRepositoryImpl implements PurchaseOrderRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    public List<PurchaseOrdersRecord> find(long companyId, PurchaseOrderQuery query) {
        int offset = (query.getPage() - 1) * query.getPageSize();
        
        return dsl.selectFrom(PURCHASE_ORDERS)
                .where(buildConditions(companyId, query))
                .orderBy(PURCHASE_ORDERS.CREATED_AT.desc())
                .limit(query.getPageSize())
                .offset(offset)
                .fetch();
    }

    @Override
    public int count(long companyId, PurchaseOrderQuery query) {
        return dsl.selectCount()
                .from(PURCHASE_ORDERS)
                .where(buildConditions(companyId, query))
                .fetchOne(0, int.class);
    }

    private List<org.jooq.Condition> buildConditions(long companyId, PurchaseOrderQuery query) {
        var conditions = new java.util.ArrayList<org.jooq.Condition>();
        conditions.add(PURCHASE_ORDERS.COMPANY_ID.eq(companyId));
        conditions.add(PURCHASE_ORDERS.DELETED_AT.isNull()); // Filter out deleted records
        // Filter by projectId if provided
        // Include: POs with that project_id OR provider-synced POs (project_id IS NULL AND provider IS NOT NULL)
        if (query.getProjectId() != null) {
            conditions.add(
                PURCHASE_ORDERS.PROJECT_ID.eq(query.getProjectId())
                    .or(PURCHASE_ORDERS.PROJECT_ID.isNull().and(PURCHASE_ORDERS.PROVIDER.isNotNull()))
            );
        }
        if (query.getDisplayId() != null) {
            conditions.add(PURCHASE_ORDERS.DISPLAY_ID.eq(query.getDisplayId()));
        }
        if (query.getDueDate() != null) {
            conditions.add(PURCHASE_ORDERS.DUE_DATE.eq(query.getDueDate().toLocalDate()));
        }
        
        // Full-text search on purchase_orders search_vector
        if (query.getSearch() != null && !query.getSearch().isBlank()) {
            String prefixQuery = com.tosspaper.models.utils.PostgresSearchUtils.buildPrefixQuery(query.getSearch());
            
            if (!prefixQuery.isEmpty()) {
                // Search on purchase_orders search_vector column using tsvector @@ tsquery
                conditions.add(org.jooq.impl.DSL.condition(
                    "search_vector @@ to_tsquery('english', ?)",
                    prefixQuery
                ));
            }
        }
        
        conditions.addAll(QueryConditionBuilder.buildBaseConditions(query, PURCHASE_ORDERS.STATUS, PURCHASE_ORDERS.CREATED_AT, PURCHASE_ORDERS.ID));
        return conditions;
    }

    @Override
    public List<PurchaseOrderFlatItemsRecord> findById(String id) {
        // This method can be improved by using the flat view to get items in one go.
        // For now, it just fetches the main record.
        return dsl.selectFrom(PURCHASE_ORDER_FLAT_ITEMS)
                .where(PURCHASE_ORDER_FLAT_ITEMS.PURCHASE_ORDER_ID.eq(id))
                .and(PURCHASE_ORDER_FLAT_ITEMS.DELETED_AT.isNull()) // Filter out deleted records
                .orderBy(PURCHASE_ORDER_FLAT_ITEMS.ITEM_ID.asc())
                .fetch();
    }

    @Override
    public PurchaseOrdersRecord create(PurchaseOrdersRecord purchaseOrder, List<PurchaseOrderItems> items) {
        try {
            return dsl.transactionResult(configuration -> {
                var dslContext = DSL.using(configuration);
                
                // Ensure displayId is set if provided in the input
                // The database trigger will only generate one if displayId is NULL
                var createdOrder = dslContext.insertInto(PURCHASE_ORDERS)
                        .set(purchaseOrder)
                        .returning()
                        .fetchOne();

                if (items != null && !items.isEmpty()) {
                    var itemRecords = items.stream()
                            .map(item -> {
                                var record = dslContext.newRecord(PURCHASE_ORDER_ITEMS);
                                record.from(item);
                                record.setPurchaseOrderId(createdOrder.getId());
                                return record;
                            })
                            .toList();
                    dslContext.batchInsert(itemRecords).execute();
                }
                return createdOrder;
            });
        } catch (DuplicateKeyException e) {
            throw new DuplicateException(
                ApiErrorMessages.PURCHASE_ORDER_ALREADY_EXISTS_CODE,
                "Purchase order with id '" + purchaseOrder.getDisplayId() + "' already exists"
            );
        }
    }

    @Override
    public PurchaseOrdersRecord update(PurchaseOrdersRecord purchaseOrder, List<PurchaseOrderItems> items, List<ChangeLogEntry> changes) {
        try {
            return dsl.transactionResult(configuration -> {
                var dslContext = DSL.using(configuration);
                 // Update the main purchase order record
                purchaseOrder.setUpdatedAt(OffsetDateTime.now());

                // Use selective field updates to avoid overwriting provider-managed fields
                // Only update user-editable fields that are not null, never touch provider/externalId/providerVersion/etc
                var updateQuery = dslContext.update(PURCHASE_ORDERS)
                    .set(PURCHASE_ORDERS.UPDATED_AT, purchaseOrder.getUpdatedAt());

                // Only update fields that are not null to preserve existing values
                if (purchaseOrder.getDisplayId() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.DISPLAY_ID, purchaseOrder.getDisplayId());
                }
                if (purchaseOrder.getOrderDate() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.ORDER_DATE, purchaseOrder.getOrderDate());
                }
                if (purchaseOrder.getDueDate() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.DUE_DATE, purchaseOrder.getDueDate());
                }
                if (purchaseOrder.getStatus() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.STATUS, purchaseOrder.getStatus());
                }
                if (purchaseOrder.getVendorContact() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.VENDOR_CONTACT, purchaseOrder.getVendorContact());
                }
                if (purchaseOrder.getShipToContact() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.SHIP_TO_CONTACT, purchaseOrder.getShipToContact());
                }
                if (purchaseOrder.getNotes() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.NOTES, purchaseOrder.getNotes());
                }
                if (purchaseOrder.getMetadata() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.METADATA, purchaseOrder.getMetadata());
                }
                if (purchaseOrder.getCurrencyCode() != null) {
                    updateQuery = updateQuery.set(PURCHASE_ORDERS.CURRENCY_CODE, purchaseOrder.getCurrencyCode());
                }

                if (!changes.isEmpty()) {
                    try {
                        String changesJson = objectMapper.writeValueAsString(changes);
                        updateQuery = updateQuery.set(PURCHASE_ORDERS.CHANGE_LOG, DSL.field("change_log || ?::jsonb", PURCHASE_ORDERS.CHANGE_LOG.getDataType(), DSL.val(changesJson)));
                    } catch (Exception e) {
                        log.warn("Could not serialize changelog to string", e);
                    }
                }

                updateQuery.where(PURCHASE_ORDERS.ID.eq(purchaseOrder.getId()))
                        .execute();

                if (items != null) {

                    // Delete existing items
                    dslContext.deleteFrom(PURCHASE_ORDER_ITEMS)
                        .where(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(purchaseOrder.getId()))
                        .execute();

                    var itemRecords = items.stream()
                        .map(item -> {
                            var record = dslContext.newRecord(PURCHASE_ORDER_ITEMS);
                            record.from(item);
                            record.setPurchaseOrderId(purchaseOrder.getId());
                            return record;
                        })
                        .toList();

                    if (!itemRecords.isEmpty()) {
                        dslContext.batchInsert(itemRecords).execute();
                    }
                }

                return purchaseOrder;
            });


        } catch (DuplicateKeyException e) {
            throw new DuplicateException(
                ApiErrorMessages.PURCHASE_ORDER_ALREADY_EXISTS_CODE,
                ApiErrorMessages.PURCHASE_ORDER_ALREADY_EXISTS
            );
        }
    }

    @Override
    public PurchaseOrdersRecord updateStatus(String id, String status, ChangeLogEntry changeLogEntry) {
        try {
            var entryJson = objectMapper.writeValueAsString(changeLogEntry);
            return dsl.update(PURCHASE_ORDERS)
                    .set(PURCHASE_ORDERS.STATUS, status)
                    .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
                    .set(PURCHASE_ORDERS.CHANGE_LOG,
                            DSL.field("change_log || ?::jsonb",
                                    PURCHASE_ORDERS.CHANGE_LOG.getDataType(), DSL.val(entryJson)))
                    .where(PURCHASE_ORDERS.ID.eq(id))
                    .returning()
                    .fetchSingle();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<PurchaseOrdersRecord> findByDisplayIdAndAssignedEmail(String displayId, String assignedEmail) {
        return dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.DISPLAY_ID.eq(displayId))
                .and(PURCHASE_ORDERS.COMPANY_ID.eq(
                    DSL.select(COMPANIES.ID)
                        .from(COMPANIES)
                        .where(COMPANIES.ASSIGNED_EMAIL.eq(assignedEmail))
                ))
                .and(PURCHASE_ORDERS.DELETED_AT.isNull()) // Filter out deleted records
                .fetchOptional();
    }
    
    @Override
    public Optional<PurchaseOrdersRecord> findByCompanyIdAndDisplayId(Long companyId, String displayId) {
        return dsl.selectFrom(PURCHASE_ORDERS)
                .where(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
                .and(PURCHASE_ORDERS.DISPLAY_ID.eq(displayId))
                .and(PURCHASE_ORDERS.DELETED_AT.isNull()) // Filter out deleted records
                .fetchOptional();
    }
    
    @Override
    public boolean updateStatusToInProgressIfPending(DSLContext ctx, String purchaseOrderId, Long companyId) {
        int updated = ctx.update(PURCHASE_ORDERS)
            .set(PURCHASE_ORDERS.STATUS, com.tosspaper.generated.model.PurchaseOrderStatus.IN_PROGRESS.getValue())
            .set(PURCHASE_ORDERS.UPDATED_AT, OffsetDateTime.now())
            .where(PURCHASE_ORDERS.ID.eq(purchaseOrderId))
            .and(PURCHASE_ORDERS.COMPANY_ID.eq(companyId))
            .and(PURCHASE_ORDERS.STATUS.eq(com.tosspaper.generated.model.PurchaseOrderStatus.PENDING.getValue()))
            .execute();
        
        return updated > 0;
    }
} 