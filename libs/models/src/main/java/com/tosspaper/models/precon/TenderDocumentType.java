package com.tosspaper.models.precon;

/**
 * Mutually exclusive classification types for construction tender documents.
 *
 * <p>Each document in a tender package belongs to exactly one type. The classifier
 * assigns the type with the most keyword hits from that type's exclusive keyword set.
 * When no type's keywords are found, or when the document cannot be parsed,
 * {@link #UNKNOWN} is returned.
 *
 * <p>The type is forwarded to Reducto with each extraction submission so that
 * Reducto can apply the correct extraction schema for that document category.
 *
 * <h3>Type descriptions</h3>
 * <ul>
 *   <li>{@link #BILL_OF_QUANTITIES} — the priced list of work items (BOQ / schedule of
 *       rates / preambles). The primary costing document in a construction tender.</li>
 *   <li>{@link #DRAWINGS} — architectural and engineering drawings, plans, and
 *       graphical specifications. Identified by drawing-list headers and sheet
 *       reference blocks.</li>
 *   <li>{@link #SPECIFICATIONS} — technical specifications describing materials,
 *       workmanship standards, and scope of work. Also covers method statements
 *       and technical requirements.</li>
 *   <li>{@link #CONDITIONS_OF_CONTRACT} — the legal/contractual framework:
 *       general conditions, special conditions, contract data, and employer's
 *       requirements.</li>
 *   <li>{@link #TENDER_NOTICE} — the invitation to tender, tender notice, or
 *       cover/index page that introduces the tender package.</li>
 *   <li>{@link #PRELIMINARIES} — the preliminaries / prelims section covering
 *       site establishment, contractor's general obligations, and fixed
 *       on-cost items not allocated to individual work sections.</li>
 *   <li>{@link #UNKNOWN} — fallback when classification yields no confident match.</li>
 * </ul>
 */
public enum TenderDocumentType {

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
