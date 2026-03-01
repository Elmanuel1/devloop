package com.tosspaper.precon;

import java.util.List;

/**
 * Immutable value object carrying all data an extraction worker needs.
 *
 * Built at enqueue time so the worker requires NO database reload:
 * all tender, extraction, and document identifiers are snapshotted here.
 *
 * @param tenderId      the tender this extraction targets
 * @param extractionId  the extraction row ID (already persisted when enqueued)
 * @param documents     ordered list of document IDs to process
 */
public record ExtractionContext(
        String tenderId,
        String extractionId,
        List<String> documents
) {
    /**
     * Defensive copy constructor — callers cannot mutate the document list
     * after construction.
     */
    public ExtractionContext {
        documents = List.copyOf(documents);
    }
}
