package com.tosspaper.precon;

/**
 * Classifies a document based on its raw bytes before it is submitted to Reducto.
 *
 * <p>Classification is performed locally — Reducto has no classify endpoint.
 * The purpose is to detect unsupported document types early and skip them
 * before incurring a full Reducto extraction call.
 *
 * <p>This is <em>not</em> file-format validation. The classifier does not verify
 * that a PDF is well-formed or that a DOCX is internally consistent; it only
 * determines whether the magic bytes indicate a type that Reducto can process.
 *
 * <p>Implementations are injectable strategies — swap the default
 * {@link MagicByteDocumentClassifier} for a richer classifier (e.g. one that
 * calls a third-party service) without changing the caller.
 */
public interface DocumentClassifier {

    /**
     * Returns {@code true} if the supplied header bytes represent a document type
     * that Reducto can process; {@code false} if the document should be skipped.
     *
     * @param documentId the document ID (used for logging only)
     * @param headerBytes the first N bytes of the document (typically 4–8 KB)
     * @return {@code true} when the type is supported; {@code false} to skip
     */
    boolean isSupported(String documentId, byte[] headerBytes);
}
