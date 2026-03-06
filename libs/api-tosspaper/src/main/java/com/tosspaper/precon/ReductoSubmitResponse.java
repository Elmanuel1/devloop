package com.tosspaper.precon;

/**
 * Response from a successful Reducto document submission.
 *
 * @param taskId the external Reducto task identifier; used to correlate the
 *               webhook callback when the extraction completes. Stored in
 *               {@code extractions.document_external_ids} (JSONB map of
 *               {@code documentId → taskId}).
 * @param fileId the Reducto file identifier returned alongside the task ID;
 *               stored in {@code tender_documents.external_file_id}. May be
 *               {@code null} if Reducto does not return a file ID for this
 *               submission type.
 */
public record ReductoSubmitResponse(String taskId, String fileId) {}
