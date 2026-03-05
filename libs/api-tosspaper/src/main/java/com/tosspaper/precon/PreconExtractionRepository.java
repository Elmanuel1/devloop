package com.tosspaper.precon;

import java.util.List;
import java.util.Optional;

/**
 * Repository for extraction pipeline operations.
 */
public interface PreconExtractionRepository {

    /**
     * Atomically claims up to {@code limit} {@code PENDING} extractions by
     * transitioning them to {@code PROCESSING} using {@code FOR UPDATE SKIP LOCKED}.
     *
     * @param limit maximum number of rows to claim (must be &gt; 0)
     * @return list of claimed extractions with their parsed document IDs
     */
    List<ExtractionWithDocs> claimNextBatch(int limit);

    /**
     * Resets stuck {@code PROCESSING} rows older than {@code staleMinutes} back
     * to {@code PENDING} so the next poll cycle can retry them.
     *
     * @param staleMinutes age threshold in minutes
     * @return number of rows reset
     */
    int reapStaleExtractions(int staleMinutes);

    /**
     * Transitions the given extraction to {@code completed} and persists the
     * pipeline result.
     *
     * @param id     the extraction ID to mark
     * @param result the combined pipeline result containing extracted fields
     * @return number of rows updated
     */
    int markAsCompleted(String id, PipelineExtractionResult result);

    /**
     * Transitions the given extraction to {@code failed} and records the error
     * reason.
     *
     * @param id          the extraction ID to mark
     * @param errorReason human-readable description of the failure cause
     * @return number of rows updated
     */
    int markAsFailed(String id, String errorReason);

    /**
     * Records the Reducto submission identifiers for a single document within
     * an extraction. Merges the entry into the {@code document_external_ids}
     * JSONB map using {@code jsonb_set}, so existing entries for other documents
     * are preserved.
     *
     * @param extractionId the extraction that owns the document
     * @param documentId   the document being submitted to Reducto
     * @param externalId   the task and file IDs returned by Reducto
     * @return number of rows updated (0 if the extraction does not exist or is deleted)
     */
    int putDocumentExternalId(String extractionId, String documentId, ExternalId externalId);

    /**
     * Finds the extraction whose {@code document_external_ids} map contains an
     * entry with the given {@code externalTaskId} as a value. Used by the webhook
     * handler to route an inbound Reducto callback to the owning extraction.
     *
     * @param externalTaskId the Reducto job ID from the webhook payload
     * @return the matching extraction with its parsed document IDs, or empty if not found
     */
    Optional<ExtractionWithDocs> findByDocumentExternalTaskId(String externalTaskId);

}
