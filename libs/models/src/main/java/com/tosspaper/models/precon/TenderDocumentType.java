package com.tosspaper.models.precon;

/**
 * Abstraction for tender document type classifications; implemented by enums such as
 * {@link ConstructionDocumentType}. Allows callers to depend on the interface rather
 * than any specific classification scheme.
 */
public interface TenderDocumentType {

    /** Returns the stable enum constant name, e.g. {@code "BILL_OF_QUANTITIES"}. */
    String name();
}
