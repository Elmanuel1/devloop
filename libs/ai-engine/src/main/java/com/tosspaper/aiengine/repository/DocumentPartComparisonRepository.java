package com.tosspaper.aiengine.repository;

import com.tosspaper.models.extraction.dto.Comparison;
import org.jooq.DSLContext;

import java.util.Optional;

/**
 * Repository for managing document comparison results.
 * Stores comparison results as JSONB for flexible querying.
 */
public interface DocumentPartComparisonRepository {

    /**
     * Save or update comparison result (upsert by extraction_id).
     * Supports retry scenarios where comparison is re-run.
     *
     * @param context the JOOQ DSL context (for transaction support)
     * @param extractionId the extraction ID
     * @param result the comparison result to save
     */
    void upsert(DSLContext context, String extractionId, Comparison result);

    /**
     * Delete comparison result for an extraction.
     *
     * @param context the JOOQ DSL context (for transaction support)
     * @param extractionId the extraction ID
     * @return number of deleted records (0 or 1)
     */
    int deleteByExtractionId(DSLContext context, String extractionId);

    /**
     * Find comparison result for an extraction.
     *
     * @param extractionId the extraction ID
     * @return the comparison result if found
     */
    Optional<Comparison> findByExtractionId(String extractionId);

    /**
     * Find comparison result for an extraction task by assigned ID.
     * Verifies company ownership through extraction_task join.
     *
     * @param assignedId the extraction task assigned ID
     * @param companyId the company ID for ownership verification
     * @return the comparison result if found
     */
    Optional<Comparison> findByAssignedId(String assignedId, Long companyId);
}
