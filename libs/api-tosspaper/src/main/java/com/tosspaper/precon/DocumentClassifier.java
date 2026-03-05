package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;
import com.tosspaper.models.precon.TenderDocumentType;

import java.io.InputStream;

/**
 * Classifies a construction tender document before it is submitted to Reducto.
 *
 * <p>Classification is performed locally — Reducto has no classify endpoint.
 * The result is a {@link TenderDocumentType} that uniquely identifies the
 * document category. The type is forwarded to Reducto so it can apply the
 * correct extraction schema for that document category.
 *
 * <p>{@link ConstructionDocumentType#UNKNOWN} is returned when the document cannot
 * be parsed or when no keyword set produces a confident match. The caller
 * ({@link ExtractionWorker}) skips documents classified as {@code UNKNOWN}.
 *
 * <p>Implementations are injectable strategies — swap the default
 * {@link PdfBoxDocumentClassifier} for a different classifier (e.g. one backed
 * by an ML service) without changing the caller.
 */
public interface DocumentClassifier {

    /**
     * Classifies the document and returns its {@link TenderDocumentType}.
     *
     * <p>The classifier owns reading from {@code contentStream}. The caller must
     * not read the stream before or after calling this method.
     *
     * @param documentId    the document ID (used for logging only)
     * @param contentStream an {@link InputStream} over the full document content
     * @return the classified {@link TenderDocumentType}; never {@code null}.
     *         Returns {@link ConstructionDocumentType#UNKNOWN} for unrecognised documents.
     */
    TenderDocumentType classify(String documentId, InputStream contentStream);
}
