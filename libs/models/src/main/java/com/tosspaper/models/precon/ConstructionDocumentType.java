package com.tosspaper.models.precon;

/**
 * Mutually exclusive classification types for construction tender documents.
 * The classifier assigns the type with the most keyword hits; {@link #UNKNOWN} is
 * returned when no keywords match or the document cannot be parsed.
 * The type is forwarded to Reducto so it applies the correct extraction schema.
 */
public enum ConstructionDocumentType implements TenderDocumentType {

    /**
     * Bill of Quantities — priced list of work items.
     * Also covers schedule of rates and preambles.
     */
    BILL_OF_QUANTITIES,

    /**
     * Architectural / engineering drawings and plans.
     */
    DRAWINGS,

    /**
     * Technical specifications and scope of work.
     */
    SPECIFICATIONS,

    /**
     * Contract conditions — general, special, and employer's requirements.
     */
    CONDITIONS_OF_CONTRACT,

    /**
     * Invitation to tender / tender notice / cover page.
     */
    TENDER_NOTICE,

    /**
     * Preliminaries — site establishment, general obligations, fixed on-costs.
     */
    PRELIMINARIES,

    /**
     * Fallback — no confident classification was possible.
     * Documents with this type are skipped and not submitted to Reducto.
     */
    UNKNOWN
}
