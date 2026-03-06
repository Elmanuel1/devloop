package com.tosspaper.precon;

import com.tosspaper.models.exception.ReductoClientException;

/**
 * Submits individual documents to Reducto for async AI extraction.
 * Reducto has no batch endpoint; each document is submitted separately with a webhook URL
 * for result delivery. The default implementation is {@link HttpReductoClient}.
 */
public interface ReductoClient {

    /**
     * Submits one document to Reducto and returns the task and file IDs.
     *
     * @throws ReductoClientException if the HTTP call fails or Reducto returns a non-2xx status
     */
    ReductoSubmitResponse submit(ReductoSubmitRequest request);
}
