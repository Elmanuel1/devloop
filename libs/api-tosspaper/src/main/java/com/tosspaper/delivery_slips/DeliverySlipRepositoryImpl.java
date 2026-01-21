package com.tosspaper.delivery_slips;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.jooq.tables.records.DeliverySlipsRecord;
import com.tosspaper.models.domain.DeliverySlip;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static com.tosspaper.models.jooq.Tables.DELIVERY_SLIPS;

/**
 * JOOQ implementation for delivery slip repository operations.
 * ObjectMapper is only used to serialize POJOs to JSONB for database storage.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DeliverySlipRepositoryImpl implements DeliverySlipRepository {
    
    private final ObjectMapper objectMapper; // Only for POJO → JSONB serialization
    private final DSLContext dsl;
    
    @Override
    public DeliverySlipsRecord create(DeliverySlip deliverySlip) {
        return create(dsl, deliverySlip);
    }

    @Override
    public DeliverySlipsRecord create(DSLContext ctx, DeliverySlip deliverySlip) {
        log.info("Creating delivery slip - extractionTaskId: {}, documentNumber: {}",
                deliverySlip.getAssignedId(), deliverySlip.getDocumentNumber());

        try {
            // Serialize party info Maps to JSONB
            String sellerInfoJson = deliverySlip.getSellerInfo() != null ? objectMapper.writeValueAsString(deliverySlip.getSellerInfo()) : null;
            String buyerInfoJson = deliverySlip.getBuyerInfo() != null ? objectMapper.writeValueAsString(deliverySlip.getBuyerInfo()) : null;
            String shipToInfoJson = deliverySlip.getShipToInfo() != null ? objectMapper.writeValueAsString(deliverySlip.getShipToInfo()) : null;
            String billToInfoJson = deliverySlip.getBillToInfo() != null ? objectMapper.writeValueAsString(deliverySlip.getBillToInfo()) : null;
            String lineItemsJson = objectMapper.writeValueAsString(deliverySlip.getLineItems());
            String shipmentDetailsJson = deliverySlip.getShipmentDetails() != null
                ? objectMapper.writeValueAsString(deliverySlip.getShipmentDetails())
                : null;
            String deliveryAckJson = deliverySlip.getDeliveryAcknowledgement() != null
                ? objectMapper.writeValueAsString(deliverySlip.getDeliveryAcknowledgement())
                : null;
            
            // NOTE: JOOQ column constants (SELLER_INFO, BUYER_INFO, etc.) will be available after running migrations and regenerating JOOQ
            var createdRecord = ctx.insertInto(DELIVERY_SLIPS)
                .set(DELIVERY_SLIPS.EXTRACTION_TASK_ID, deliverySlip.getAssignedId())
                .set(DELIVERY_SLIPS.COMPANY_ID, deliverySlip.getCompanyId())
                .set(DELIVERY_SLIPS.DOCUMENT_NUMBER, deliverySlip.getDocumentNumber())
                .set(DELIVERY_SLIPS.DOCUMENT_DATE, deliverySlip.getDocumentDate())
                .set(DELIVERY_SLIPS.PROJECT_ID, deliverySlip.getProjectId())
                .set(DELIVERY_SLIPS.PROJECT_NAME, deliverySlip.getProjectName())
                .set(DELIVERY_SLIPS.JOB_NUMBER, deliverySlip.getJobNumber())
                .set(DELIVERY_SLIPS.PO_NUMBER, deliverySlip.getPoNumber())
                .set(DELIVERY_SLIPS.DELIVERY_METHOD_NOTE, deliverySlip.getDeliveryMethodNote())
                .set(DELIVERY_SLIPS.SELLER_INFO, JSONB.jsonbOrNull(sellerInfoJson))
                .set(DELIVERY_SLIPS.BUYER_INFO, JSONB.jsonbOrNull(buyerInfoJson))
                .set(DELIVERY_SLIPS.SHIP_TO_INFO, JSONB.jsonbOrNull(shipToInfoJson))
                .set(DELIVERY_SLIPS.BILL_TO_INFO, JSONB.jsonbOrNull(billToInfoJson))
                .set(DELIVERY_SLIPS.LINE_ITEMS, JSONB.jsonbOrNull(lineItemsJson))
                .set(DELIVERY_SLIPS.SHIPMENT_DETAILS, JSONB.jsonbOrNull(shipmentDetailsJson))
                .set(DELIVERY_SLIPS.DELIVERY_ACKNOWLEDGEMENT, JSONB.jsonbOrNull(deliveryAckJson))
                .set(DELIVERY_SLIPS.STATUS, DeliverySlip.Status.DELIVERED.getValue())
                .returning()
                .fetchSingle();

            log.info("Created delivery slip record for extraction task {}: {} (ID: {})",
                deliverySlip.getAssignedId(), deliverySlip.getDocumentNumber(), createdRecord.getId());
            
            return createdRecord;
        } catch (Exception e) {
            log.error("Failed to create delivery slip for extraction task {}", deliverySlip.getAssignedId(), e);
            throw new RuntimeException("Failed to create delivery slip", e);
        }
    }
    
    @Override
    public List<DeliverySlipsRecord> findDeliverySlips(Long companyId, DeliverySlipQuery query) {
        List<Condition> conditions = new ArrayList<>();
        
        // Always filter by companyId (from X-Context-Id header)
        conditions.add(DELIVERY_SLIPS.COMPANY_ID.eq(companyId));
        
        if (query.getProjectId() != null) {
            conditions.add(DELIVERY_SLIPS.PROJECT_ID.eq(query.getProjectId()));
        }
        if (query.getPoNumber() != null) {
            conditions.add(DELIVERY_SLIPS.PO_NUMBER.eq(query.getPoNumber()));
        }
        
        // Use buildBaseConditions for created date filters and cursor
        // Delivery slips don't have status field, so pass null
        conditions.addAll(com.tosspaper.common.query.QueryConditionBuilder.buildBaseConditions(
            query,
            null, // statusField - delivery slips don't have status
            DELIVERY_SLIPS.CREATED_AT,
            DELIVERY_SLIPS.ID
        ));
        
        // Full-text search on delivery_slips search_vector (no join needed)
        if (query.getSearch() != null && !query.getSearch().trim().isEmpty()) {
            String prefixQuery = com.tosspaper.models.utils.PostgresSearchUtils.buildPrefixQuery(query.getSearch());
            
            if (!prefixQuery.isEmpty()) {
                // Search on delivery_slips search_vector column using tsvector @@ tsquery
                conditions.add(org.jooq.impl.DSL.condition(
                    "search_vector @@ to_tsquery('english', ?)",
                    prefixQuery
                ));
            }
        }
        
        return dsl.selectFrom(DELIVERY_SLIPS)
            .where(conditions)
            .orderBy(DELIVERY_SLIPS.CREATED_AT.desc(), DELIVERY_SLIPS.ID.desc())
            .limit(query.getPageSize())
            .fetch();
    }

    @Override
    public DeliverySlipsRecord findByAssignedId(String extractionTaskId) {
        return dsl.selectFrom(DELIVERY_SLIPS)
                .where(DELIVERY_SLIPS.EXTRACTION_TASK_ID.eq(extractionTaskId))
                .fetchSingle();
    }

    @Override
    public DeliverySlipsRecord findById(String id) {
        return dsl.selectFrom(DELIVERY_SLIPS)
                .where(DELIVERY_SLIPS.ID.eq(id))
                .fetchOne();
    }
}

