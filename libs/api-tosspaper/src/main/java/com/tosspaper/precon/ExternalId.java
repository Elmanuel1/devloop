package com.tosspaper.precon;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Identifies a single document's Reducto submission within an extraction.
 *
 * <p>Stored as values in the {@code document_external_ids} JSONB map on the
 * {@code extractions} table, keyed by document ID.
 *
 * @param externalTaskId the async job ID returned by Reducto when the document
 *                       is submitted — used to correlate inbound webhooks
 * @param externalFileId the Reducto file-upload ID — allows the ExtractionWorker
 *                       to skip re-uploading the same file on retry
 */
public record ExternalId(
        @JsonProperty("externalTaskId") String externalTaskId,
        @JsonProperty("externalFileId") String externalFileId
) {}
