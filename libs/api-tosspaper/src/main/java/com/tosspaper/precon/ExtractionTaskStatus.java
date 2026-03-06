package com.tosspaper.precon;

/** Status of a Reducto extraction task, returned by {@link ExtractionClient#getTask(String)}. */
public record ExtractionTaskStatus(
        String taskId,
        String status,
        String reason
) {}
