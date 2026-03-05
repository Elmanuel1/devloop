package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;

import java.util.List;

/**
 * Value object pairing a pending {@link ExtractionsRecord} with the parsed
 * list of document IDs stored in its {@code document_ids} JSONB column.
 *
 * <p>Built by {@link PreconExtractionRepositoryImpl#claimNextBatch(int)}
 * so that the poll job never needs a second DB round-trip to discover which
 * documents belong to an extraction.
 *
 * <p>The document list is defensively copied on construction.
 *
 * @param extraction the raw extraction record
 * @param documentIds the ordered list of document IDs to process
 */
public record ExtractionWithDocs(
        ExtractionsRecord extraction,
        List<String> documentIds
) {
    public ExtractionWithDocs {
        documentIds = List.copyOf(documentIds);
    }

    /** Convenience accessor for the extraction's primary key. */
    public String getId() {
        return extraction.getId();
    }
}
