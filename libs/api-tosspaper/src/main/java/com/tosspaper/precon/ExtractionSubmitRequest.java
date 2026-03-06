package com.tosspaper.precon;

import com.tosspaper.models.precon.ConstructionDocumentType;

/** Payload sent to the extraction backend for a single document submission. */
public record ExtractionSubmitRequest(
        String extractionId,
        String documentId,
        String s3Key,
        byte[] fileBytes,
        String webhookUrl,
        ConstructionDocumentType documentType,
        String externalFileId
) {}
