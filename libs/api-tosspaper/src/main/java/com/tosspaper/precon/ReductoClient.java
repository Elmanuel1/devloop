package com.tosspaper.precon;

import com.tosspaper.models.exception.ReductoClientException;

/** Submits individual documents to Reducto for async AI extraction. */
public interface ReductoClient {

    ReductoSubmitResponse submit(ReductoSubmitRequest request);
}
