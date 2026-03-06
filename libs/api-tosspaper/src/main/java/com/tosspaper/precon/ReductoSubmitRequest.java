package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;

/** Payload sent to Reducto for a single document extraction submission. */
public record ReductoSubmitRequest(
        String extractionId,
        String documentId,
        String s3Key,
        byte[] fileBytes,
        String webhookUrl,
        ConstructionDocumentType documentType
) {}
