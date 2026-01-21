package com.tosspaper.invoices;

import com.tosspaper.models.jooq.tables.records.InvoicesRecord;
import com.tosspaper.models.domain.Invoice;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Repository for invoice operations.
 * Uses domain models instead of OpenAPI schemas.
 */
public interface InvoiceRepository {

    /**
     * Create an invoice record from domain model.
     * Uses the repository's injected DSLContext (non-transactional).
     *
     * @param invoice the invoice domain model
     * @return the created invoice record from database
     */
    InvoicesRecord create(Invoice invoice);

    /**
     * Create an invoice record from domain model.
     * Uses the provided DSLContext (for transactional operations).
     *
     * @param ctx the JOOQ DSL context (for transactional operations)
     * @param invoice the invoice domain model
     * @return the created invoice record from database
     */
    InvoicesRecord create(DSLContext ctx, Invoice invoice);
    
    /**
     * Find invoices with filtering and cursor pagination.
     *
     * @param companyId filter by company ID (required, from X-Context-Id header)
     * @param query invoice query containing filters, search, cursor, and limit
     * @return list of invoice records ordered by created_at DESC, id DESC
     */
    List<InvoicesRecord> findInvoices(Long companyId, InvoiceQuery query);

    InvoicesRecord findInvoiceByAssignedId(String extractionTaskId);

    /**
     * Find invoice by ID (simple lookup, no companyId check).
     *
     * @param id the invoice ID
     * @return invoice record or null if not found
     */
    InvoicesRecord findById(String id);

}

