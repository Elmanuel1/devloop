package com.tosspaper.precon;

/**
 * Carries the result of processing a single document through the extraction
 * pipeline (e.g. via Reducto or another AI backend).
 *
 * <p>Fields are intentionally minimal — this is a placeholder until the real
 * extraction engine is wired in TOS-38. The {@code documentId} and
 * {@code extractedText} fields will be expanded as the engine API stabilises.
 *
 * @param documentId    the document that was processed
 * @param extractedText the raw text returned by the extraction backend,
 *                      or {@code null} when not yet implemented
 */
public record DocResult(
        String documentId,
        String extractedText
) {}
