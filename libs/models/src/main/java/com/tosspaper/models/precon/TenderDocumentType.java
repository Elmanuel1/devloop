package com.tosspaper.models.precon;

/**
 * Base type for all tender document type classifications. Implementations must be enums.
 *
 * <p>This interface acts as the abstraction layer for document classification so that
 * callers (e.g. {@code DocumentClassifier}, {@code ExtractionWorker}) depend on the
 * interface rather than any specific classification scheme. A concrete implementation
 * (e.g. {@link ConstructionDocumentType}) provides the actual enum constants.
 *
 * <p>Because Java enums can implement interfaces, any procurement domain can supply
 * its own enum that implements this interface without modifying the classifier
 * contract.
 *
 * @see ConstructionDocumentType
 */
public interface TenderDocumentType {

    /**
     * Returns a stable, human-readable code for this document type.
     *
     * <p>For enums, the default implementation delegates to {@link Enum#name()},
     * so no override is required in concrete enums.
     *
     * @return the enum constant name, e.g. {@code "BILL_OF_QUANTITIES"}
     */
    String name();
}
