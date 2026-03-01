package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;

import java.util.List;

/**
 * Repository for extraction pipeline operations.
 *
 * <p>This replaces {@link ExtractionRepository} for all new pipeline callers.
 * Do NOT add new callers to the deprecated {@code ExtractionRepository}.
 *
 * <p>Key methods:
 * <ul>
 *   <li>{@link #findByExternalTaskId(String)} — look up an extraction by the
 *       opaque task ID returned by the external extraction service. The name is
 *       intentionally generic (not tied to any specific provider).</li>
 *   <li>{@link #findPendingExtractions()} — returns all live pending rows for
 *       the seeder to enqueue on startup.</li>
 *   <li>{@link #updateStatus(String, String)} — atomically updates status and
 *       increments version.</li>
 * </ul>
 */
public interface PreconExtractionRepository {

    /**
     * Finds a non-deleted extraction by the opaque external task ID that was
     * assigned by the external extraction service upon submission.
     *
     * <p>The parameter name is generic — it is not specific to any particular
     * extraction provider (e.g. Reducto, Textract, etc.).
     *
     * @param externalTaskId the task ID returned by the external service
     * @return the matching extraction record
     * @throws com.tosspaper.common.NotFoundException if no live record matches
     */
    ExtractionsRecord findByExternalTaskId(String externalTaskId);

    /**
     * Returns all non-deleted extractions currently in {@code pending} status.
     * Used by the seeder to re-enqueue work that survived a process restart.
     *
     * @return list of pending extraction records (may be empty, never null)
     */
    List<ExtractionsRecord> findPendingExtractions();

    /**
     * Updates the status of an extraction and atomically increments its
     * optimistic-lock version. Skips soft-deleted rows.
     *
     * @param id     the extraction ID
     * @param status the new status value
     * @return number of rows updated (0 if not found or already deleted)
     */
    int updateStatus(String id, String status);
}
