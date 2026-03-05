package com.tosspaper.precon;

/**
 * Response from a successful Reducto document submission.
 *
 * @param taskId the external Reducto task identifier; used to correlate
 *               the webhook callback when the extraction completes
 */
public record ReductoSubmitResponse(String taskId) {}
