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
 *   <li>{@link #findPendingExtractions(int)} — returns up to {@code limit}
 *       pending rows for the poll job to dispatch per cycle.</li>
 *   <li>{@link #markAsProcessing(String)} — atomically transitions a single
 *       extraction from {@code pending} to {@code processing} within the
 *       poll cycle, before handing the record off to the processing thread pool.</li>
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
     * Returns up to {@code limit} non-deleted extractions currently in
     * {@code pending} status, ordered by creation time ascending so that
     * older work is prioritised.
     *
     * <p>The caller controls the batch size; the repository does not hard-code
     * any polling policy.
     *
     * @param limit maximum number of rows to return (must be &gt; 0)
     * @return list of pending extraction records (may be empty, never null)
     */
    List<ExtractionsRecord> findPendingExtractions(int limit);

    /**
     * Atomically transitions the given extraction to {@code processing} status
     * and increments its version.
     *
     * <p>Must be called in the scheduler thread — before the record is handed
     * off to the processing thread pool — so that a subsequent poll cycle does
     * not re-fetch the same record as {@code pending}.
     *
     * @param id the extraction ID to mark
     * @return number of rows updated (0 if not found or already deleted)
     */
    int markAsProcessing(String id);
}
