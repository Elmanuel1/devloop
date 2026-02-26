package com.tosspaper.common;

public final class ApiErrorMessages {

    private ApiErrorMessages() {
        // Private constructor to prevent instantiation
    }

    public static final String COMPANY_ALREADY_EXISTS = "Company with email %s already exists.";
    public static final String COMPANY_ASSIGNED_EMAIL_ALREADY_EXISTS = "The assigned email '%s' is already assigned to another company.";
    public static final String COMPANY_NOT_FOUND = "Company not found.";
    public static final String CONTACT_NOT_FOUND = "Contact not found.";
    public static final String CONTACT_ALREADY_EXISTS = "A contact with the same name, email, or phone number already exists in your organization.";
    public static final String CONTACT_NOT_ACTIVE = "Contact is not active.";
    public static final String CONTACT_DELETE_CONSTRAINT_VIOLATION = "Contact cannot be deleted because it is still reference by your project";
    public static final String CONTACT_EMAIL_OR_PHONE_REQUIRED = "Either email or phone number is required for a contact.";
    public static final String PROJECT_NOT_FOUND = "Project not found.";
    public static final String PROJECT_ALREADY_EXISTS = "Another project in your organization already uses the key '%s'. Please use a different key.";
    public static final String PROJECT_IS_NOT_ACTIVE = "Project is in a state that does not allow updates.";
    public static final String ACCESS_DENIED_TO_PROJECT = "You do not have access to project";
    public static final String ACCESS_DENIED_TO_CONTACT = "Access denied to contact";
    public static final String PURCHASE_ORDER_NOT_FOUND = "Purchase order not found.";
    public static final String PURCHASE_ORDER_ILLEGAL_STATE = "You cannot change the status of this purchase order";
    public static final String PURCHASE_ORDER_ILLEGAL_STATE_TRANSITION = "Cannot transition purchase order from %s to %s";
    public static final String PURCHASE_ORDER_ALREADY_EXISTS = "Purchase order already exists. Please use a different configuration.";
    public static final String PURCHASE_ORDER_DISPLAY_ID_UPDATE_NOT_ALLOWED = "Purchase order display ID can only be changed while the order is pending.";
    public static final String PROPERTY_NOT_FOUND = "Property not found or you do not have access to it.";
    public static final String PROPERTY_ALREADY_EXISTS = "Property with the same address already exists in your organization.";
    
    // Project status transition messages
    public static final String PROJECT_STATUS_INVALID = "Invalid project status";
    public static final String PROJECT_STATUS_TRANSITION_FROM_ARCHIVED = "Cannot transition project from %s to %s. Archived projects can only be reactivated";
    public static final String PROJECT_STATUS_TRANSITION_FROM_FINAL = "Cannot transition project from %s to %s. Project status updates are not allowed from this state";
    public static final String PROJECT_UPDATE_NOT_ALLOWED_FINAL_STATE = "Cannot update project in %s state. Projects in final states (completed, cancelled) cannot be modified";
    public static final String INVALID_HEADER_FORMAT = "api.validation.error";
    public static final String INVALID_CONTEXT_ID_FORMAT = "Invalid X-Context-Id header format: '%s'. Expected a valid company ID number.";

    // File upload error messages
    public static final String FILE_SIZE_TOO_LARGE = "Please upload a file less than %d MB";
    public static final String FILE_SIZE_INVALID = "File size must be greater than 0";
    public static final String FILE_EXTENSION_REQUIRED = "File must have a valid extension";
    public static final String FILE_EXTENSION_MISMATCH = "File extension is not supported";
    public static final String CONTENT_TYPE_UNSUPPORTED = "File uploaded is Unsupported";

    public static final String EMAIL_VERIFICATION_REQUIRED = "Email verification required. Please check your email and verify your account.";
    public static final String UNAUTHORIZED = "Please login to access this resource";
    // Add other error messages here
    public static final String ERROR_PROCESSING_REQUEST = "Error processing request";
    public static final String REQUEST_PART_MUST_BE_PROVIDED = "%s: request part must be provided";
    public static final String REQUIRED_HEADER_IS_MISSING = "Required header '%s' is missing";

    public static final String INTERNAL_ERROR_CODE = "api.internal.error";

    public static final String COMPANY_NOT_FOUND_CODE = "api.company.notFound";
    public static final String PROJECT_NOT_FOUND_CODE = "api.project.notFound";
    public static final String PROPERTY_NOT_FOUND_CODE = "api.property.notFound";
    public static final String CONTACT_NOT_FOUND_CODE = "api.contact.notFound";
    public static final String PURCHASE_ORDER_NOT_FOUND_CODE = "api.purchaseOrder.notFound";
    public static final String ITEM_NOT_FOUND_CODE = "api.item.notFound";
    public static final String ITEM_NOT_FOUND = "Item not found.";
    public static final String ITEM_ALREADY_EXISTS_CODE = "api.item.duplicate";
    public static final String ITEM_ALREADY_EXISTS = "An item with the same name already exists in your organization.";
    public static final String ACCESS_DENIED_TO_ITEM = "Access denied to item";

    public static final String COMPANY_ALREADY_EXISTS_CODE = "api.company.duplicate";
    public static final String COMPANY_ASSIGNED_EMAIL_ALREADY_EXISTS_CODE = "api.company.assigned_email.duplicate";
    public static final String CONTACT_ALREADY_EXISTS_CODE = "api.contact.duplicate";
    public static final String CONTACT_EMAIL_OR_PHONE_REQUIRED_CODE = "api.contact.emailOrPhoneRequired";
    public static final String PROPERTY_ALREADY_EXISTS_CODE = "api.property.duplicate";
    public static final String PURCHASE_ORDER_ALREADY_EXISTS_CODE = "api.purchaseOrder.duplicate";

    public static final String INVALID_ETAG_CODE = "api.validation.invalidETag";
    public static final String INVALID_ETAG = "Invalid ETag format. Expected format: \"v{version}\"";

    public static final String FORBIDDEN_CODE = "api.forbidden";
    public static final String FORBIDDEN_DOMAIN_CODE = "api.forbidden.domain";
    public static final String INTERNAL_SERVER_ERROR_CODE = "api.internalError";

    // Tender error messages
    public static final String TENDER_NOT_FOUND_CODE = "api.tender.notFound";
    public static final String TENDER_NOT_FOUND = "Tender not found";

    // Document error messages
    public static final String DOCUMENT_NOT_FOUND_CODE = "api.document.notFound";
    public static final String DOCUMENT_NOT_FOUND = "Document not found";
    public static final String DOCUMENT_NOT_READY_CODE = "api.document.notReady";
    public static final String DOCUMENT_NOT_READY = "Document is not ready for download. Current status: %s";
    public static final String DOCUMENT_CANNOT_DELETE_CODE = "api.document.cannotDelete";
    public static final String DOCUMENT_CANNOT_DELETE = "Cannot delete documents from a tender in '%s' status";

    // Cursor error messages
    public static final String INVALID_CURSOR_CODE = "api.validation.invalidCursor";
    public static final String INVALID_CURSOR = "Invalid cursor format";

    // ── Extraction ──
    public static final String EXTRACTION_NOT_FOUND_CODE      = "api.extraction.notFound";
    public static final String EXTRACTION_NOT_FOUND           = "Extraction not found.";
    public static final String EXTRACTION_CANNOT_CANCEL_CODE  = "api.extraction.cannotCancel";
    public static final String EXTRACTION_CANNOT_CANCEL       = "Cannot cancel extraction in '%s' status.";
    public static final String EXTRACTION_NO_READY_DOCS_CODE  = "api.extraction.noReadyDocuments";
    public static final String EXTRACTION_NO_READY_DOCS       = "No ready documents found for entity '%s'. Upload and wait for documents to reach ready status before starting extraction.";
    public static final String EXTRACTION_DOC_NOT_OWNED_CODE  = "api.extraction.documentNotOwned";
    public static final String EXTRACTION_DOC_NOT_OWNED       = "Document '%s' does not belong to entity '%s'.";
    public static final String EXTRACTION_INVALID_FIELD_CODE  = "api.extraction.invalidField";
    public static final String EXTRACTION_INVALID_FIELD       = "Field name '%s' is not valid for entity type '%s'.";
    public static final String EXTRACTION_FIELD_NOT_FOUND_CODE = "api.extraction.field.notFound";
    public static final String EXTRACTION_FIELD_NOT_FOUND      = "Extraction field not found.";

    // ── Application ──
    public static final String APPLICATION_UNRESOLVED_CONFLICTS_CODE = "api.application.unresolvedConflicts";
    public static final String APPLICATION_UNRESOLVED_CONFLICTS      = "Cannot apply extraction — %d field(s) have conflicts that must be resolved before applying. Set edited_value on each conflicted field via PATCH /v1/extractions/{id}/fields.";
    public static final String APPLICATION_ENTITY_STALE_CODE = "api.application.entityStale";
    public static final String APPLICATION_ENTITY_STALE      = "Cannot apply extraction — the target entity was modified after this extraction was created. Please review the current state and start a new extraction if needed.";
    public static final String APPLICATION_NO_FIELDS_CODE    = "api.application.noFields";
    public static final String APPLICATION_NO_FIELDS         = "No extraction fields found targeting entity '%s'.";
} 