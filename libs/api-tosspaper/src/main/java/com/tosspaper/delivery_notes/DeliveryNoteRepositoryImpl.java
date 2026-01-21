package com.tosspaper.delivery_notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.jooq.tables.records.DeliveryNotesRecord;
import com.tosspaper.models.domain.DeliveryNote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static com.tosspaper.models.jooq.Tables.DELIVERY_NOTES;

/**
 * JOOQ implementation for delivery note repository operations.
 * ObjectMapper is only used to serialize POJOs to JSONB for database storage.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DeliveryNoteRepositoryImpl implements DeliveryNoteRepository {

    private final ObjectMapper objectMapper;
    private final DSLContext dsl;

    @Override
    public DeliveryNotesRecord create(DeliveryNote deliveryNote, String extractionTaskId, Long companyId, String projectId, String poNumber, String createdBy) {
        return create(dsl, deliveryNote);
    }

    @Override
    public DeliveryNotesRecord create(DSLContext ctx, DeliveryNote deliveryNote) {
        try {
            String sellerInfoJson = deliveryNote.getSellerInfo() != null ? objectMapper.writeValueAsString(deliveryNote.getSellerInfo()) : null;
            String buyerInfoJson = deliveryNote.getBuyerInfo() != null ? objectMapper.writeValueAsString(deliveryNote.getBuyerInfo()) : null;
            String shipToInfoJson = deliveryNote.getShipToInfo() != null ? objectMapper.writeValueAsString(deliveryNote.getShipToInfo()) : null;
            String billToInfoJson = deliveryNote.getBillToInfo() != null ? objectMapper.writeValueAsString(deliveryNote.getBillToInfo()) : null;
            String lineItemsJson = objectMapper.writeValueAsString(deliveryNote.getLineItems());
            String shipmentDetailsJson = deliveryNote.getShipmentDetails() != null ? objectMapper.writeValueAsString(deliveryNote.getShipmentDetails()) : null;
            String deliveryAckJson = deliveryNote.getDeliveryAcknowledgement() != null ? objectMapper.writeValueAsString(deliveryNote.getDeliveryAcknowledgement()) : null;

            var createdRecord = ctx.insertInto(DELIVERY_NOTES)
                .set(DELIVERY_NOTES.EXTRACTION_TASK_ID, deliveryNote.getAssignedId())
                .set(DELIVERY_NOTES.COMPANY_ID, deliveryNote.getCompanyId())
                .set(DELIVERY_NOTES.DOCUMENT_NUMBER, deliveryNote.getDocumentNumber())
                .set(DELIVERY_NOTES.DOCUMENT_DATE, deliveryNote.getDocumentDate())
                .set(DELIVERY_NOTES.PROJECT_ID, deliveryNote.getProjectId())
                .set(DELIVERY_NOTES.JOB_NUMBER, deliveryNote.getJobNumber())
                .set(DELIVERY_NOTES.PO_NUMBER, deliveryNote.getPoNumber())
                .set(DELIVERY_NOTES.SELLER_INFO, JSONB.jsonbOrNull(sellerInfoJson))
                .set(DELIVERY_NOTES.BUYER_INFO, JSONB.jsonbOrNull(buyerInfoJson))
                .set(DELIVERY_NOTES.SHIP_TO_INFO, JSONB.jsonbOrNull(shipToInfoJson))
                .set(DELIVERY_NOTES.BILL_TO_INFO, JSONB.jsonbOrNull(billToInfoJson))
                .set(DELIVERY_NOTES.LINE_ITEMS, JSONB.jsonbOrNull(lineItemsJson))
                .set(DELIVERY_NOTES.SHIPMENT_DETAILS, JSONB.jsonbOrNull(shipmentDetailsJson) )
                .set(DELIVERY_NOTES.DELIVERY_ACKNOWLEDGEMENT, JSONB.jsonbOrNull(deliveryAckJson))
                .set(DELIVERY_NOTES.STATUS, DeliveryNote.Status.ACCEPTED.getValue())
                .returning()
                .fetchSingle();

            log.info("Created delivery note record for extraction task {}: {} (ID: {})",
                deliveryNote.getAssignedId(), deliveryNote.getDocumentNumber(), createdRecord.getId());

            return createdRecord;
        } catch (Exception e) {
            log.error("Failed to create delivery note for extraction task {}", deliveryNote.getAssignedId(), e);
            throw new RuntimeException("Failed to create delivery note", e);
        }
    }

    @Override
    public List<DeliveryNotesRecord> findDeliveryNotes(Long companyId, DeliveryNoteQuery query) {
        List<Condition> conditions = new ArrayList<>();

        // Always filter by companyId (from X-Context-Id header)
        conditions.add(DELIVERY_NOTES.COMPANY_ID.eq(companyId));

        if (query.getProjectId() != null) {
            conditions.add(DELIVERY_NOTES.PROJECT_ID.eq(query.getProjectId()));
        }
        if (query.getPoNumber() != null) {
            conditions.add(DELIVERY_NOTES.PO_NUMBER.eq(query.getPoNumber()));
        }

        // Use buildBaseConditions for created date filters and cursor
        conditions.addAll(com.tosspaper.common.query.QueryConditionBuilder.buildBaseConditions(
            query,
            DELIVERY_NOTES.STATUS,
            DELIVERY_NOTES.CREATED_AT,
            DELIVERY_NOTES.ID
        ));

        // Full-text search on delivery_notes search_vector
        if (query.getSearch() != null && !query.getSearch().trim().isEmpty()) {
            String prefixQuery = com.tosspaper.models.utils.PostgresSearchUtils.buildPrefixQuery(query.getSearch());

            if (!prefixQuery.isEmpty()) {
                // Search on delivery_notes search_vector column using tsvector @@ tsquery
                conditions.add(org.jooq.impl.DSL.condition(
                    "search_vector @@ to_tsquery('english', ?)",
                    prefixQuery
                ));
            }
        }

        return dsl.selectFrom(DELIVERY_NOTES)
            .where(conditions)
            .orderBy(DELIVERY_NOTES.CREATED_AT.desc(), DELIVERY_NOTES.ID.desc())
            .limit(query.getPageSize())
            .fetch();
    }

    @Override
    public DeliveryNotesRecord findByAssignedId(String extractionTaskId) {
        return dsl.selectFrom(DELIVERY_NOTES)
                .where(DELIVERY_NOTES.EXTRACTION_TASK_ID.eq(extractionTaskId))
                .fetchSingle();
    }

    @Override
    public DeliveryNotesRecord findById(String id) {
        return dsl.selectFrom(DELIVERY_NOTES)
                .where(DELIVERY_NOTES.ID.eq(id))
                .fetchOne();
    }
}
