package com.tosspaper.delivery_slips;

import com.tosspaper.models.jooq.tables.records.DeliverySlipsRecord;
import com.tosspaper.models.domain.DeliverySlip;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Repository for delivery slip operations.
 */
public interface DeliverySlipRepository {

    /**
     * Create a delivery slip record from domain model.
     * Uses the repository's injected DSLContext (non-transactional).
     *
     * @param deliverySlip the delivery slip domain model
     * @return the created delivery slip record from database
     */
    DeliverySlipsRecord create(DeliverySlip deliverySlip);

    /**
     * Create a delivery slip record from domain model.
     * Uses the provided DSLContext (for transactional operations).
     *
     * @param ctx the JOOQ DSL context (for transactional operations)
     * @param deliverySlip the delivery slip domain model
     * @return the created delivery slip record from database
     */
    DeliverySlipsRecord create(DSLContext ctx, DeliverySlip deliverySlip);
    
    /**
     * Find delivery slips with filtering and cursor pagination.
     *
     * @param companyId filter by company ID (required, from X-Context-Id header)
     * @param query query object containing filters, cursor, and pagination parameters
     * @return list of delivery slip records ordered by created_at DESC, id DESC
     */
    List<DeliverySlipsRecord> findDeliverySlips(Long companyId, DeliverySlipQuery query);

    /**
     * Find delivery slip by extraction task ID.
     *
     * @param extractionTaskId the extraction task ID
     * @return delivery slip record
     */
    DeliverySlipsRecord findByAssignedId(String extractionTaskId);

    /**
     * Find delivery slip by ID (simple lookup, no companyId check).
     *
     * @param id the delivery slip ID
     * @return delivery slip record or null if not found
     */
    DeliverySlipsRecord findById(String id);
}

