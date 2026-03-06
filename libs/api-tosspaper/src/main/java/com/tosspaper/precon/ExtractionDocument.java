package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;

import java.util.List;

/**
 * Value object pairing a pending {@link ExtractionsRecord} with its pre-loaded
 * {@link TenderDocumentsRecord} list — built by
 * {@link PreconExtractionRepositoryImpl#claimNextBatch(int)} so the worker
 * never needs a second DB round-trip.
 *
 * @param extraction the raw extraction record
 * @param documents  the pre-loaded documents for this extraction
 */
public record ExtractionDocument(
        ExtractionsRecord extraction,
        List<TenderDocumentsRecord> documents
) {
    public ExtractionDocument {
        documents = List.copyOf(documents);
    }

    /** Convenience accessor for the extraction's primary key. */
    public String getId() {
        return extraction.getId();
    }
}
