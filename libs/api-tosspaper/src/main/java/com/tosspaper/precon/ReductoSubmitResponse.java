package com.tosspaper.precon;

/** Response from a successful Reducto document submission. */
public record ReductoSubmitResponse(String taskId, String fileId) {}
