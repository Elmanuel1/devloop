package com.tosspaper.precon;

/**
 * Response from a successful Reducto document submission.
 *
 * @param taskId Reducto task ID; correlated to the inbound webhook callback and stored in
 *               {@code extractions.document_external_ids}.
 * @param fileId Reducto file ID stored in {@code tender_documents.external_file_id};
 *               may be {@code null}.
 */
public record ReductoSubmitResponse(String taskId, String fileId) {}
