package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the per-document extraction pipeline for a single
 * {@link ExtractionWithDocs}.
 *
 * <h3>Per-document steps</h3>
 * <ol>
 *   <li><b>Classify</b> — stream the full document from S3 and pass to
 *       {@link DocumentClassifier#isSupported}. The default implementation
 *       ({@link PdfBoxDocumentClassifier}) extracts PDF text via Apache PDFBox
 *       and applies keyword matching. Unsupported types are skipped.</li>
 *   <li><b>Submit</b> — call {@link ReductoClient#submit} with the document's S3 key
 *       and the configured webhook URL. One call per document; Reducto has no
 *       batch endpoint.</li>
 * </ol>
 *
 * <h3>Hard cap</h3>
 * <p>At most {@value #MAX_DOCUMENTS_PER_BATCH} documents are processed per extraction.
 *
 * <h3>Idempotency</h3>
 * <p>The worker is safe to call multiple times for the same extraction. Step 1 (classify)
 * is a stateless S3 read. Step 2 (submit) calls Reducto and returns a task ID — if the
 * extraction was already submitted in a prior run, the webhook handler will simply receive
 * a duplicate delivery which is handled idempotently via {@code ON CONFLICT DO NOTHING}.
 *
 * <h3>Integration with ExtractionPipelineRunner</h3>
 * <p>This class is the implementation of the {@code callReducto} stub that was
 * left as a {@code TODO [TOS-38]} placeholder in {@link ExtractionPipelineRunner}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionWorker {

    /** Hard cap on documents processed per extraction batch. */
    static final int MAX_DOCUMENTS_PER_BATCH = 20;

    private final DocumentClassifier documentClassifier;
    private final ReductoClient reductoClient;
    private final ExtractionFieldValidator fieldValidator;
    private final TenderDocumentRepository documentRepository;
    private final ReductoProperties reductoProperties;
    private final S3Client s3Client;
    private final TenderFileProperties fileProperties;

    /**
     * Processes all documents in the given extraction, returning a
     * {@link PipelineExtractionResult} that summarises the outcome.
     *
     * <p>A failure in one document does not abort the remaining documents —
     * each document is processed independently and failures are collected.
     *
     * @param extraction the claimed extraction with its document list
     * @return combined pipeline result for all processed documents
     */
    public PipelineExtractionResult process(ExtractionWithDocs extraction) {
        List<String> docIds = extraction.documentIds();
        int cap = Math.min(docIds.size(), MAX_DOCUMENTS_PER_BATCH);

        if (docIds.size() > MAX_DOCUMENTS_PER_BATCH) {
            log.warn("[ExtractionWorker] Extraction '{}' has {} documents — capping at {}",
                    extraction.getId(), docIds.size(), MAX_DOCUMENTS_PER_BATCH);
        }

        List<String> failedDocIds = new ArrayList<>();

        for (int i = 0; i < cap; i++) {
            String documentId = docIds.get(i);
            boolean ok = processDocument(extraction.getId(), documentId);
            if (!ok) {
                failedDocIds.add(documentId);
            }
        }

        if (!failedDocIds.isEmpty()) {
            log.warn("[ExtractionWorker] Extraction '{}' — {} document(s) failed to submit: {}",
                    extraction.getId(), failedDocIds.size(), failedDocIds);
        }

        return new PipelineExtractionResult(extraction.getId(), null);
    }

    /**
     * Validates a Reducto payload and signals whether fields may be written.
     *
     * <p>Called externally by the webhook handler once Reducto delivers the result.
     * Validation must pass before any {@code extraction_fields} rows are written via
     * {@link ExtractionFieldService}.
     *
     * @param extractionId the extraction ID
     * @param documentId   the document ID this payload belongs to
     * @param payload      the JSONB result from Reducto
     * @return {@code true} if validation passed; {@code false} if the payload was rejected
     */
    public boolean validateAndWriteFields(String extractionId, String documentId, JsonNode payload) {
        if (!fieldValidator.isValid(documentId, payload)) {
            log.warn("[ExtractionWorker] Extraction '{}' document '{}' — {}",
                    extractionId, documentId,
                    ApiErrorMessages.EXTRACTION_FIELD_INVALID_PAYLOAD.formatted(documentId));
            return false;
        }
        log.debug("[ExtractionWorker] Extraction '{}' document '{}' — payload valid, ready for field write",
                extractionId, documentId);
        return true;
    }

    // ── Per-document pipeline ─────────────────────────────────────────────────

    /**
     * Runs classify → submit for one document.
     *
     * @return {@code true} on success or supported-type skip; {@code false} on failure
     */
    boolean processDocument(String extractionId, String documentId) {
        TenderDocumentsRecord document = lookupDocument(extractionId, documentId);
        if (document == null) {
            return false;
        }

        // Step 1: classify — download full document content from S3 and pass to classifier
        InputStream contentStream = openContentStream(extractionId, documentId, document.getS3Key());
        if (contentStream == null) {
            return false;
        }
        if (!documentClassifier.isSupported(documentId, contentStream)) {
            log.info("[ExtractionWorker] Extraction '{}' document '{}' — unsupported type, skipping",
                    extractionId, documentId);
            return true; // skip is not a failure
        }

        // Step 2: submit to Reducto (one call per document — no batch API)
        return submitToReducto(extractionId, documentId, document.getS3Key());
    }

    // ── Step implementations ──────────────────────────────────────────────────

    private TenderDocumentsRecord lookupDocument(String extractionId, String documentId) {
        try {
            return documentRepository.findById(documentId);
        } catch (Exception e) {
            log.error("[ExtractionWorker] Extraction '{}' document '{}' — lookup failed: {}",
                    extractionId, documentId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Opens a full-content {@link InputStream} for a document stored in S3.
     *
     * <p>Package-private to allow spy-based unit test overrides without mocking
     * the non-mockable {@link ResponseInputStream} class from the AWS SDK.
     *
     * @return an {@link InputStream} over the document bytes, or {@code null} on any failure
     */
    InputStream openContentStream(String extractionId, String documentId, String s3Key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(fileProperties.getUploadBucket())
                    .key(s3Key)
                    .build();
            // Read all bytes eagerly so the HTTP connection can be released before classification.
            // PDFBox may need to seek back in the stream; wrapping in ByteArrayInputStream is safe.
            try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request)) {
                byte[] bytes = stream.readAllBytes();
                return new ByteArrayInputStream(bytes);
            }
        } catch (IOException | SdkException e) {
            log.error("[ExtractionWorker] Extraction '{}' document '{}' — S3 content download failed: {}",
                    extractionId, documentId, e.getMessage(), e);
            return null;
        }
    }

    private boolean submitToReducto(String extractionId, String documentId, String s3Key) {
        try {
            ReductoSubmitRequest request = new ReductoSubmitRequest(
                    extractionId,
                    documentId,
                    s3Key,
                    reductoProperties.buildWebhookUrl()
            );
            ReductoSubmitResponse response = reductoClient.submit(request);
            log.info("[ExtractionWorker] Extraction '{}' document '{}' submitted — taskId='{}'",
                    extractionId, documentId, response.taskId());
            return true;
        } catch (ReductoClientException e) {
            log.error("[ExtractionWorker] Extraction '{}' document '{}' — Reducto submission failed: {}",
                    extractionId, documentId, e.getMessage(), e);
            return false;
        }
    }
}
