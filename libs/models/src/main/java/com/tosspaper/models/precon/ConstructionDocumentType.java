package com.tosspaper.models.precon;

/** Classification types for construction tender documents. */
public enum ConstructionDocumentType implements TenderDocumentType {

    /** Bill of Quantities — priced list of work items. */
    BILL_OF_QUANTITIES,

    /** Architectural / engineering drawings and plans. */
    DRAWINGS,

    /** Technical specifications and scope of work. */
    SPECIFICATIONS,

    /** Contract conditions — general, special, and employer's requirements. */
    CONDITIONS_OF_CONTRACT,

    /** Invitation to tender / tender notice / cover page. */
    TENDER_NOTICE,

    /** Preliminaries — site establishment and general obligations. */
    PRELIMINARIES,

    /** Fallback — no confident classification was possible. */
    UNKNOWN;

    /** Returns the matching type for the given string, or {@code UNKNOWN} for null or unrecognised values. */
    public static ConstructionDocumentType fromValue(String value) {
        if (value == null) return UNKNOWN;
        try {
            return valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
