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
 *       opaque task ID returned by the external extraction service.</li>
 *   <li>{@link #claimNextBatch(int)} — atomically claims up to {@code limit}
 *       {@code PENDING} rows by transitioning them to {@code PROCESSING} using
 *       {@code FOR UPDATE SKIP LOCKED}, so concurrent poll threads never claim
 *       the same row.</li>
 *   <li>{@link #reapStaleExtractions(int)} — resets {@code PROCESSING} rows
 *       that have been stuck beyond the stale threshold back to {@code PENDING},
 *       allowing the next poll cycle to retry them.</li>
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
     * Atomically claims up to {@code limit} {@code PENDING} extractions by
     * transitioning them to {@code PROCESSING} in a single statement using
     * {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p>Because the claim and status update happen inside one CTE, concurrent
     * poll threads in different JVM instances never claim the same row — no
     * application-level distributed lock is required.
     *
     * <p>Results are ordered by {@code created_at} ascending so that older
     * work is prioritised.
     *
     * @param limit maximum number of rows to claim (must be &gt; 0)
     * @return list of claimed extractions with their parsed document IDs
     *         (may be empty, never null)
     */
    List<ExtractionWithDocs> claimNextBatch(int limit);

    /**
     * Resets stuck {@code PROCESSING} rows back to {@code PENDING}.
     *
     * <p>Any extraction whose {@code updated_at} is older than
     * {@code staleMinutes} is assumed to have been abandoned (e.g. the
     * processing node crashed) and is returned to the queue so the next
     * poll cycle can retry it.
     *
     * @param staleMinutes age threshold in minutes; rows older than this are
     *                     considered stale
     * @return number of rows reset
     */
    int reapStaleExtractions(int staleMinutes);

    /**
     * Atomically transitions the given extraction to {@code completed} status
     * and increments its version.
     *
     * @param id the extraction ID to mark
     * @return number of rows updated (0 if not found or already deleted)
     */
    int markAsCompleted(String id);

    /**
     * Atomically transitions the given extraction to {@code failed} status
     * and increments its version.
     *
     * @param id the extraction ID to mark
     * @return number of rows updated (0 if not found or already deleted)
     */
    int markAsFailed(String id);
}
