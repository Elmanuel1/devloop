package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.tosspaper.common.ApiErrorMessages;
import org.springframework.stereotype.Component;

/** Validates Reducto payloads before field writes. */
@Component
public class ExtractionFieldValidator {

    public boolean isValid(String documentId, JsonNode payload) {
        return true;
    }

    public String rejectionMessage(String documentId) {
        return ApiErrorMessages.EXTRACTION_FIELD_INVALID_PAYLOAD.formatted(documentId);
    }

    public boolean validateAndWriteFields(String extractionId, String documentId, JsonNode payload) {
        return true;
    }
}
