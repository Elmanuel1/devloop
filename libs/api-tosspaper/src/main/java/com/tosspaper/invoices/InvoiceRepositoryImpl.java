package com.tosspaper.invoices;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.jooq.tables.records.InvoicesRecord;
import com.tosspaper.models.domain.Invoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static com.tosspaper.models.jooq.Tables.INVOICES;

/**
 * JOOQ implementation for invoice repository operations.
 * ObjectMapper is only used to serialize POJOs to JSONB for database storage.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class InvoiceRepositoryImpl implements InvoiceRepository {
    
    private final ObjectMapper objectMapper; // Only for POJO → JSONB serialization
    private final DSLContext dsl;
    private final InvoiceMapper invoiceMapper;
    
    @Override
    public com.tosspaper.models.jooq.tables.records.InvoicesRecord create(Invoice invoice) {
        return create(dsl, invoice);
    }

    @Override
    public com.tosspaper.models.jooq.tables.records.InvoicesRecord create(DSLContext ctx, Invoice invoice) {
        try {
            // Serialize party info Maps to JSONB
            String sellerInfoJson = invoice.getSellerInfo() != null ? objectMapper.writeValueAsString(invoice.getSellerInfo()) : null;
            String buyerInfoJson = invoice.getBuyerInfo() != null ? objectMapper.writeValueAsString(invoice.getBuyerInfo()) : null;
            String shipToInfoJson = invoice.getShipToInfo() != null ? objectMapper.writeValueAsString(invoice.getShipToInfo()) : null;
            String billToInfoJson = invoice.getBillToInfo() != null ? objectMapper.writeValueAsString(invoice.getBillToInfo()) : null;
            String lineItemsJson = objectMapper.writeValueAsString(invoice.getLineItems());
            
            // NOTE: JOOQ column constants (SELLER_INFO, BUYER_INFO, etc.) will be available after running migrations and regenerating JOOQ
            var createdRecord = ctx.insertInto(INVOICES)
                .set(INVOICES.EXTRACTION_TASK_ID, invoice.getAssignedId())
                .set(INVOICES.COMPANY_ID, invoice.getCompanyId())
                .set(INVOICES.DOCUMENT_NUMBER, invoice.getDocumentNumber())
                .set(INVOICES.DOCUMENT_DATE, invoice.getDocumentDate())
                .set(INVOICES.PROJECT_ID, invoice.getProjectId())
                .set(INVOICES.PROJECT_NAME, (String) null) // Not in extraction schema
                .set(INVOICES.PO_NUMBER, invoice.getPoNumber())
                .set(INVOICES.ORDER_TICKET_NUMBER, (String) null) // Not in extraction schema
                .set(INVOICES.SELLER_INFO, JSONB.jsonbOrNull(sellerInfoJson))
                .set(INVOICES.BUYER_INFO, JSONB.jsonbOrNull(buyerInfoJson))
                .set(INVOICES.SHIP_TO_INFO, JSONB.jsonbOrNull(shipToInfoJson))
                .set(INVOICES.BILL_TO_INFO, JSONB.jsonbOrNull(billToInfoJson))
                .set(INVOICES.LINE_ITEMS, JSONB.jsonb(lineItemsJson))
                .set(INVOICES.STATUS, Invoice.Status.PENDING.getValue())
                .set(INVOICES.INVOICE_DETAILS, JSONB.jsonbOrNull(invoiceMapper.toInvoiceDetail(invoice.getInvoiceDetails())))
                .returning()
                .fetchSingle();

            log.info("Created invoice record for extraction task {}: {} (ID: {})",
                invoice.getAssignedId(), invoice.getDocumentNumber(), createdRecord.getId());
            
            return createdRecord;
        } catch (Exception e) {
            log.error("Failed to create invoice for extraction task {}", invoice.getAssignedId(), e);
            throw new RuntimeException("Failed to create invoice", e);
        }
    }
    
    @Override
    public List<InvoicesRecord> findInvoices(Long companyId, InvoiceQuery query) {
        List<Condition> conditions = new ArrayList<>();
        
        // Always filter by companyId (from X-Context-Id header)
        conditions.add(INVOICES.COMPANY_ID.eq(companyId));
        
        if (query.getProjectId() != null) {
            conditions.add(INVOICES.PROJECT_ID.eq(query.getProjectId()));
        }
        if (query.getPoNumber() != null) {
            conditions.add(INVOICES.PO_NUMBER.eq(query.getPoNumber()));
        }
        
        // Use buildBaseConditions for status, cursor (using created_at for cursor)
        // For invoices, use createdDateFrom/createdDateTo from BaseQuery to filter received_at
        if (query.getStatus() != null) {
            conditions.add(INVOICES.STATUS.eq(query.getStatus()));
        }
        
        // Use BaseQuery's createdDateFrom/createdDateTo to filter received_at
        if (query.getCreatedDateFrom() != null) {
            conditions.add(INVOICES.RECEIVED_AT.ge(query.getCreatedDateFrom()));
        }
        if (query.getCreatedDateTo() != null) {
            conditions.add(INVOICES.RECEIVED_AT.le(query.getCreatedDateTo()));
        }

        // Cursor pagination: WHERE (created_at < cursor) OR (created_at = cursor AND id < cursor_id)
        if (query.getCursorCreatedAt() != null && query.getCursorId() != null) {
            Condition cursorCondition = INVOICES.CREATED_AT.lessThan(query.getCursorCreatedAt())
                .or(INVOICES.CREATED_AT.eq(query.getCursorCreatedAt()).and(INVOICES.ID.lessThan(query.getCursorId())));
            conditions.add(cursorCondition);
        }
        
        // Full-text search on invoices search_vector (no join needed)
        if (query.getSearch() != null && !query.getSearch().trim().isEmpty()) {
            String prefixQuery = com.tosspaper.models.utils.PostgresSearchUtils.buildPrefixQuery(query.getSearch());
            
            if (!prefixQuery.isEmpty()) {
                // Search on invoices search_vector column using tsvector @@ tsquery
                conditions.add(org.jooq.impl.DSL.condition(
                    "search_vector @@ to_tsquery('english', ?)",
                    prefixQuery
                ));
            }
        }
        
        return dsl.selectFrom(INVOICES)
            .where(conditions)
            .orderBy(INVOICES.CREATED_AT.desc(), INVOICES.ID.desc())
            .limit(query.getPageSize())
            .fetch();
    }

    @Override
    public InvoicesRecord findInvoiceByAssignedId(String assignedId) {
        return dsl.selectFrom(INVOICES)
                .where(INVOICES.EXTRACTION_TASK_ID.eq( assignedId))
                .fetchSingle();
    }

    @Override
    public InvoicesRecord findById(String id) {
        return dsl.selectFrom(INVOICES)
                .where(INVOICES.ID.eq(id))
                .fetchOne();
    }
}

