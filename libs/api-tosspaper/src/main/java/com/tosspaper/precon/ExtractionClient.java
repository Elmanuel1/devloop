package com.tosspaper.precon;

/** Submits individual documents to the extraction backend for async AI extraction. */
public interface ExtractionClient {

    ExtractionSubmitResponse submit(ExtractionSubmitRequest request);
}
