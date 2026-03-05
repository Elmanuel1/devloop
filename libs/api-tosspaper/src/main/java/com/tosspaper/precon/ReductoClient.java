package com.tosspaper.precon;

import com.tosspaper.models.exception.ReductoClientException;

/**
 * Submits individual documents to Reducto for AI extraction.
 *
 * <h3>One call per document</h3>
 * <p>Reducto has no batch endpoint. Each document in an extraction batch must be
 * submitted individually. The caller includes a {@code webhookUrl} in each request
 * so Reducto can push the result back asynchronously.
 *
 * <h3>Injectable strategy</h3>
 * <p>Implementations are injectable — the default {@link HttpReductoClient} makes
 * real HTTP calls; tests inject a mock or stub.
 */
public interface ReductoClient {

    /**
     * Submits one document to Reducto and returns the Reducto task ID.
     *
     * <p>The {@code webhookUrl} in the request tells Reducto where to deliver
     * the result once extraction is complete. The returned {@link ReductoSubmitResponse}
     * carries the {@code taskId} that uniquely identifies this submission and
     * will be present in the subsequent webhook callback.
     *
     * @param request all data needed for one document submission
     * @return Reducto's response containing the task ID
     * @throws ReductoClientException if the HTTP call fails or Reducto returns a non-2xx status
     */
    ReductoSubmitResponse submit(ReductoSubmitRequest request);
}
