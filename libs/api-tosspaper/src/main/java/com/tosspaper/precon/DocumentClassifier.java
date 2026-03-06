package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;

/** Classifies a document before Reducto submission. */
public interface DocumentClassifier {

    ConstructionDocumentType classify(String documentId, byte[] contentBytes);
}
