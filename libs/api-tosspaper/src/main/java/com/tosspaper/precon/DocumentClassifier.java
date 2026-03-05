package com.tosspaper.precon;

import java.io.InputStream;

/**
 * Classifies a document from its content before it is submitted to Reducto.
 *
 * <p>Classification is performed locally — Reducto has no classify endpoint.
 * The purpose is to determine whether a document's textual content is of a
 * type that Reducto can usefully extract (e.g. a procurement-related PDF)
 * before incurring a full Reducto extraction call.
 *
 * <p>Implementations are injectable strategies — swap the default
 * {@link PdfBoxDocumentClassifier} for a different classifier (e.g. one that
 * calls a third-party ML service) without changing the caller.
 */
public interface DocumentClassifier {

    /**
     * Returns {@code true} if the document content represents a type that
     * Reducto can usefully process; {@code false} if the document should be skipped.
     *
     * <p>The classifier owns reading from {@code contentStream}. The caller must
     * not read the stream before or after calling this method.
     *
     * @param documentId    the document ID (used for logging only)
     * @param contentStream an {@link InputStream} over the full document content
     * @return {@code true} when the document is supported; {@code false} to skip
     */
    boolean isSupported(String documentId, InputStream contentStream);
}
