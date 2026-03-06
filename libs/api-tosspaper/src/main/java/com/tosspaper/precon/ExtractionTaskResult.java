package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;

/** Full result of a completed Reducto extraction task, returned by {@link ExtractionClient#getTask(String)}. */
public record ExtractionTaskResult(
        String taskId,
        String status,
        String reason,
        JsonNode result
) {}
