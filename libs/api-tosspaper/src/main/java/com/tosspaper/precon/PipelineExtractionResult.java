package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Carries the result of processing an extraction through the pipeline.
 * The {@code fields} node is null until the Reducto engine is wired in TOS-38.
 *
 * @param extractionId the extraction this result belongs to
 * @param fields       extracted fields as a JSON object
 */
public record PipelineExtractionResult(
        String extractionId,
        JsonNode fields
) {}
