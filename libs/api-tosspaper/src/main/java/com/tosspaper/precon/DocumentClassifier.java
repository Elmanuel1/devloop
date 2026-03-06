package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;

/**
 * Classifies a construction tender document before it is submitted to Reducto.
 *
 * <p>Classification is performed locally — Reducto has no classify endpoint.
 * {@link ConstructionDocumentType#UNKNOWN} is returned when the document cannot
 * be parsed or when no keyword set produces a confident match; the caller
 * ({@link ExtractionWorker}) skips such documents.
 *
 * <p>Implementations are injectable strategies — swap the default
 * {@link PdfBoxDocumentClassifier} without changing the caller.
 */
public interface DocumentClassifier {

    /**
     * Classifies the document from its raw bytes.
     * Returns {@link ConstructionDocumentType#UNKNOWN} for unrecognised documents; never {@code null}.
     */
    ConstructionDocumentType classify(String documentId, byte[] contentBytes);
}
