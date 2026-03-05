package com.tosspaper.precon;

import java.util.Map;

/**
 * Carries the result of processing an extraction through the pipeline.
 * The {@code fields} map is empty until the Reducto engine is wired in TOS-38.
 *
 * @param extractionId the extraction this result belongs to
 * @param fields       extracted field name → value pairs
 */
public record PipelineExtractionResult(
        String extractionId,
        Map<String, Object> fields
) {

    /** Convenience factory for an empty result (used by the stub implementation). */
    public static PipelineExtractionResult empty(String extractionId) {
        return new PipelineExtractionResult(extractionId, Map.of());
    }
}
