package com.tosspaper.aiengine.constants;

/**
 * Context keys used for passing information through the comparison advisor chain.
 * These keys are used in ChatClientRequest.context() maps for stateless context passing.
 */
public final class ComparisonContextKeys {

    private ComparisonContextKeys() {
        // Utility class - prevent instantiation
    }

    /**
     * Company ID for the comparison (Long).
     */
    public static final String COMPANY_ID = "companyId";

    /**
     * Document/extraction ID being compared (String).
     */
    public static final String DOCUMENT_ID = "documentId";

    /**
     * Type of document being compared: "invoice", "delivery_slip", "delivery_note" (String).
     */
    public static final String DOCUMENT_TYPE = "documentType";

    /**
     * Purchase Order number being compared against (String).
     */
    public static final String PO_NUMBER = "poNumber";

    /**
     * Purchase Order ID (internal) being compared against (String).
     */
    public static final String PO_ID = "poId";

    /**
     * Path to the document extraction JSON file in VFS (String).
     */
    public static final String DOCUMENT_FILE_PATH = "documentFilePath";

    /**
     * Path to the PO data JSON file in VFS (String).
     */
    public static final String PO_FILE_PATH = "poFilePath";

    /**
     * Working directory for the agent (String).
     */
    public static final String WORKING_DIRECTORY = "workingDirectory";

    /**
     * Path to write comparison results (String).
     */
    public static final String RESULTS_FILE_PATH = "resultsFilePath";

    /**
     * Assigned email address for filtering (String).
     */
    public static final String ASSIGNED_EMAIL = "assignedEmail";

    /**
     * ComparisonContext object containing PurchaseOrder and ExtractionTask.
     */
    public static final String COMPARISON_CONTEXT = "comparisonContext";
}
