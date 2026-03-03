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
 *       pending rows <em>together with</em> their parsed document-ID lists,
 *       so the poll job never needs a second DB round-trip.</li>
 *   <li>{@link #markAsProcessing(String)} — atomically transitions a single
 *       extraction from {@code pending} to {@code processing} within the
 *       poll cycle, before handing the record off to the processing thread
 *       pool.</li>
 *   <li>{@link #markAsCompleted(String)} — transitions an extraction to
 *       {@code completed} once all documents have been processed.</li>
 *   <li>{@link #markAsFailed(String)} — transitions an extraction to
 *       {@code failed} when processing encounters an unrecoverable error.</li>
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
     * {@code pending} status, each paired with their parsed document IDs.
     *
     * <p>The documents are read from the {@code document_ids} JSONB column
     * and parsed inline so that the poll job requires no additional DB
     * round-trip to discover which documents belong to each extraction.
     *
     * <p>Results are ordered by {@code created_at} ascending so that older
     * work is prioritised.
     *
     * @param limit maximum number of rows to return (must be &gt; 0)
     * @return list of pending extractions with their document IDs
     *         (may be empty, never null)
     */
    List<ExtractionWithDocs> findPendingExtractions(int limit);

    /**
     * Atomically transitions the given extraction to {@code processing} status
     * and increments its version.
     *
     * <p>Must be called immediately after acquiring the per-extraction Redisson
     * lock, before dispatching documents to the thread pool, so that a
     * subsequent poll cycle does not re-fetch the same record as
     * {@code pending}.
     *
     * @param id the extraction ID to mark
     * @return number of rows updated (0 if not found or already deleted)
     */
    int markAsProcessing(String id);

    /**
     * Atomically transitions the given extraction to {@code completed} status
     * and increments its version.
     *
     * <p>Called by {@link ExtractionPollJob} after all documents for an
     * extraction have been successfully processed and the results saved.
     *
     * @param id the extraction ID to mark
     * @return number of rows updated (0 if not found or already deleted)
     */
    int markAsCompleted(String id);

    /**
     * Atomically transitions the given extraction to {@code failed} status
     * and increments its version.
     *
     * <p>Called by {@link ExtractionPollJob} when an extraction's scatter-gather
     * pipeline encounters an unrecoverable error.
     *
     * @param id the extraction ID to mark
     * @return number of rows updated (0 if not found or already deleted)
     */
    int markAsFailed(String id);
}
