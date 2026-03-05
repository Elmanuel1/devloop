package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;

/**
 * Classifies a construction tender document before it is submitted to Reducto.
 *
 * <p>Classification is performed locally — Reducto has no classify endpoint.
 * The result is a {@link ConstructionDocumentType} enum value that uniquely
 * identifies the document category. The type is forwarded to Reducto so it
 * can apply the correct extraction schema for that document category.
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
     * Classifies the document from its raw bytes and returns its
     * {@link ConstructionDocumentType}.
     *
     * <p>{@code byte[]} is preferred over {@code InputStream} because:
     * <ul>
     *   <li>PDFBox may need to seek within the document during parsing;
     *       a buffered byte array supports this without wrapping.</li>
     *   <li>The caller ({@link ExtractionWorker}) already reads all bytes from S3
     *       eagerly to release the HTTP connection, so the array is already in memory.</li>
     *   <li>A byte array can be passed to multiple classifiers without exhaustion.</li>
     * </ul>
     *
     * @param documentId   the document ID (used for logging only)
     * @param contentBytes the full raw content of the document
     * @return the classified {@link ConstructionDocumentType}; never {@code null}.
     *         Returns {@link ConstructionDocumentType#UNKNOWN} for unrecognised documents.
     */
    ConstructionDocumentType classify(String documentId, byte[] contentBytes);
}
