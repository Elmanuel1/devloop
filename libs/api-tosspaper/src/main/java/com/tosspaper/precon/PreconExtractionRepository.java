package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;

import java.util.List;
import java.util.Map;
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
     * Reads the current {@code document_external_ids} map for the given extraction.
     * Returns an empty mutable map when the extraction does not exist or the column is empty.
     * The map is keyed by document ID and the values are Reducto external task IDs.
     *
     * @param extractionId the extraction to read
     * @return a mutable copy of the current map
     */
    Map<String, String> getDocumentExternalIds(String extractionId);

    /**
     * Overwrites the {@code document_external_ids} JSONB column with the
     * provided map. The caller is responsible for building the updated map
     * (read-modify-write in the service layer).
     * The map is keyed by document ID and the values are Reducto external task IDs.
     *
     * @param extractionId        the extraction to update
     * @param documentExternalIds the complete replacement map (documentId → externalTaskId)
     * @return number of rows updated
     */
    int updateDocumentExternalIds(String extractionId, Map<String, String> documentExternalIds);

    /**
     * Finds the extraction whose {@code document_external_ids} map contains a
     * value equal to the given {@code externalTaskId}.
     *
     * @param externalTaskId the Reducto job ID to search for
     * @return an Optional containing the matching extraction, or empty if not found
     */
    Optional<ExtractionsRecord> findByDocumentExternalTaskId(String externalTaskId);

}
