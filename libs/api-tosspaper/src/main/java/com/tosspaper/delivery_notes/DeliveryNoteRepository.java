package com.tosspaper.delivery_notes;

import com.tosspaper.models.jooq.tables.records.DeliveryNotesRecord;
import com.tosspaper.models.domain.DeliveryNote;
import org.jooq.DSLContext;

import java.util.List;

/**
 * Repository for delivery note operations.
 */
public interface DeliveryNoteRepository {

    /**
     * Create a delivery note record from domain model.
     * Uses the repository's injected DSLContext (non-transactional).
     *
     * @param deliveryNote the delivery note domain model
     * @param extractionTaskId the extraction task ID
     * @param companyId the company ID
     * @param projectId the project ID (ULID from extraction_task.latest_project_id)
     * @param poNumber the PO number from matched purchase order (if any)
     * @param createdBy the user ID who approved the document
     * @return the created delivery note record from database
     */
    DeliveryNotesRecord create(DeliveryNote deliveryNote, String extractionTaskId, Long companyId, String projectId, String poNumber, String createdBy);

    /**
     * Create a delivery note record from domain model.
     * Uses the provided DSLContext (for transactional operations).
     *
     * @param ctx the JOOQ DSL context (for transactional operations)
     * @param deliveryNote the delivery note domain model
     * @return the created delivery note record from database
     */
    DeliveryNotesRecord create(DSLContext ctx, DeliveryNote deliveryNote);

    /**
     * Find delivery notes with filtering and cursor pagination.
     *
     * @param companyId filter by company ID (required, from X-Context-Id header)
     * @param query query object containing filters, cursor, and pagination parameters
     * @return list of delivery note records ordered by created_at DESC, id DESC
     */
    List<DeliveryNotesRecord> findDeliveryNotes(Long companyId, DeliveryNoteQuery query);

    /**
     * Find delivery note by extraction task ID.
     *
     * @param extractionTaskId the extraction task ID
     * @return delivery note record
     */
    DeliveryNotesRecord findByAssignedId(String extractionTaskId);

    /**
     * Find delivery note by ID (simple lookup, no companyId check).
     *
     * @param id the delivery note ID
     * @return delivery note record or null if not found
     */
    DeliveryNotesRecord findById(String id);
}
