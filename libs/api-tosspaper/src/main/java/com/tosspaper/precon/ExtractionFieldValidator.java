package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.tosspaper.common.ApiErrorMessages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates the JSONB payload returned by Reducto before any write to
 * {@code extraction_fields}.
 *
 * <h3>What is validated</h3>
 * <ul>
 *   <li>The payload is not {@code null} and is not a JSON null node.</li>
 *   <li>The payload is a JSON object (not an array or primitive).</li>
 *   <li>The payload is not an empty object.</li>
 * </ul>
 *
 * <h3>Rejection semantics</h3>
 * <p>An invalid payload is rejected and logged. The caller (ExtractionWorker) must
 * treat a rejection as a per-document failure and mark that document accordingly.
 * It must <em>not</em> write any {@code extraction_fields} rows for the rejected
 * document.
 *
 * <h3>Extensibility</h3>
 * <p>Future versions may validate against a schema file (e.g. {@code precon-tender.json})
 * once the Reducto field schema is finalised. The current implementation applies
 * structural constraints that apply regardless of the schema.
 */
@Slf4j
@Component
public class ExtractionFieldValidator {

    /**
     * Validates the Reducto response payload for a single document.
     *
     * @param documentId the document ID (used for logging)
     * @param payload    the JSONB result from Reducto
     * @return {@code true} when the payload is structurally valid; {@code false} when rejected
     */
    public boolean isValid(String documentId, JsonNode payload) {
        if (payload == null || payload.isNull()) {
            log.warn("[ExtractionFieldValidator] Rejected document '{}' — payload is null", documentId);
            return false;
        }

        if (!payload.isObject()) {
            log.warn("[ExtractionFieldValidator] Rejected document '{}' — payload is not a JSON object (type={})",
                    documentId, payload.getNodeType());
            return false;
        }

        if (payload.isEmpty()) {
            log.warn("[ExtractionFieldValidator] Rejected document '{}' — payload is an empty object", documentId);
            return false;
        }

        log.debug("[ExtractionFieldValidator] Document '{}' — payload valid ({} field(s))",
                documentId, payload.size());
        return true;
    }

    /**
     * Returns the rejection message to be stored as the per-document error reason.
     *
     * @param documentId the document ID
     * @return formatted error message constant
     */
    public String rejectionMessage(String documentId) {
        return ApiErrorMessages.EXTRACTION_FIELD_INVALID_PAYLOAD.formatted(documentId);
    }

    /**
     * Validates a Reducto payload and signals whether fields may be written.
     *
     * <p>Called by the webhook handler once Reducto delivers the result.
     * Validation must pass before any {@code extraction_fields} rows are written.
     *
     * @param extractionId the extraction ID (used for log context only)
     * @param documentId   the document ID this payload belongs to
     * @param payload      the JSONB result from Reducto
     * @return {@code true} if validation passed; {@code false} if the payload was rejected
     */
    public boolean validateAndWriteFields(String extractionId, String documentId, JsonNode payload) {
        if (!isValid(documentId, payload)) {
            log.warn("[ExtractionFieldValidator] Extraction '{}' document '{}' — {}",
                    extractionId, documentId,
                    ApiErrorMessages.EXTRACTION_FIELD_INVALID_PAYLOAD.formatted(documentId));
            return false;
        }
        log.debug("[ExtractionFieldValidator] Extraction '{}' document '{}' — payload valid, ready for field write",
                extractionId, documentId);
        return true;
    }
}
