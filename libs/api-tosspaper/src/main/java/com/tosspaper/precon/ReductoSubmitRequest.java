package com.tosspaper.precon;

/**
 * Payload sent to Reducto for a single document extraction submission.
 *
 * @param extractionId  the extraction this document belongs to
 * @param documentId    the document being submitted
 * @param s3Key         the S3 object key for the document
 * @param webhookUrl    the URL Reducto should call when extraction is complete
 */
public record ReductoSubmitRequest(
        String extractionId,
        String documentId,
        String s3Key,
        String webhookUrl
) {}
