package com.tosspaper.precon;

/** Response from a successful extraction backend document submission. */
public record ExtractionSubmitResponse(String taskId, String fileId) {}
