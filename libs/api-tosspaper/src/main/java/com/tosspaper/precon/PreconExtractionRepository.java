package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;

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
     * Stores or replaces the {@link ExternalId} for a single document within
     * the extraction's {@code document_external_ids} JSONB map.
     *
     * @param extractionId the extraction that owns the map
     * @param documentId   the key (document UUID)
     * @param externalId   the Reducto task and file identifiers to store
     * @return number of rows updated
     */
    int putDocumentExternalId(String extractionId, String documentId, ExternalId externalId);

    /**
     * Finds the extraction whose {@code document_external_ids} map contains a
     * value with the given {@code externalTaskId}.
     *
     * @param externalTaskId the Reducto job ID to search for
     * @return an Optional containing the matching extraction, or empty if not found
     */
    Optional<ExtractionsRecord> findByDocumentExternalTaskId(String externalTaskId);

}
