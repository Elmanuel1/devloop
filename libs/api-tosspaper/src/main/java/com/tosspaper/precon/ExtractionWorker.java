package com.tosspaper.precon;

import com.tosspaper.models.exception.ReductoClientException;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.models.precon.ConstructionDocumentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Orchestrates the classify → upload → extract pipeline for each document in an extraction. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionWorker {

    private final DocumentClassifier documentClassifier;
    private final ReductoClient reductoClient;
    private final TenderDocumentRepository documentRepository;
    private final PreconExtractionRepository extractionRepository;
    private final DocumentContentReader contentReader;
    private final ReductoProperties reductoProperties;
    private final ExtractionProcessingProperties processingProperties;

    public PipelineExtractionResult process(ExtractionWithDocs extraction) {
        List<String> docIds = extraction.documentIds();
        int batchSize = processingProperties.getBatchSize();
        int cap = Math.min(docIds.size(), batchSize);

        if (docIds.size() > batchSize) {
            log.warn("[ExtractionWorker] Extraction '{}' has {} documents — capping at {}",
                    extraction.getId(), docIds.size(), batchSize);
        }

        List<String> failedDocIds = new ArrayList<>();
        for (int i = 0; i < cap; i++) {
            String documentId = docIds.get(i);
            if (!processDocument(extraction.getId(), documentId)) {
                failedDocIds.add(documentId);
            }
        }

        if (!failedDocIds.isEmpty()) {
            log.warn("[ExtractionWorker] Extraction '{}' — {} document(s) failed: {}",
                    extraction.getId(), failedDocIds.size(), failedDocIds);
        }

        return new PipelineExtractionResult(extraction.getId(), null);
    }

    boolean processDocument(String extractionId, String documentId) {
        TenderDocumentsRecord document = lookupDocument(extractionId, documentId);
        if (document == null) {
            return false;
        }

        byte[] contentBytes;
        try {
            contentBytes = contentReader.read(document.getS3Key());
        } catch (Exception e) {
            log.error("[ExtractionWorker] Extraction '{}' document '{}' — S3 read failed: {}",
                    extractionId, documentId, e.getMessage(), e);
            return false;
        }

        ConstructionDocumentType documentType = documentClassifier.classify(documentId, contentBytes);
        if (documentType == ConstructionDocumentType.UNKNOWN) {
            log.warn("[ExtractionWorker] Extraction '{}' document '{}' — UNKNOWN type, skipping",
                    extractionId, documentId);
            return true;
        }

        return submitToReducto(extractionId, documentId, document.getS3Key(), contentBytes, documentType);
    }

    private TenderDocumentsRecord lookupDocument(String extractionId, String documentId) {
        try {
            return documentRepository.findById(documentId);
        } catch (Exception e) {
            log.error("[ExtractionWorker] Extraction '{}' document '{}' — lookup failed: {}",
                    extractionId, documentId, e.getMessage(), e);
            return null;
        }
    }

    private boolean submitToReducto(String extractionId, String documentId, String s3Key,
                                    byte[] fileBytes, ConstructionDocumentType documentType) {
        try {
            ReductoSubmitRequest request = new ReductoSubmitRequest(
                    extractionId,
                    documentId,
                    s3Key,
                    fileBytes,
                    reductoProperties.buildWebhookUrl(),
                    documentType
            );
            ReductoSubmitResponse response = reductoClient.submit(request);
            log.info("[ExtractionWorker] Extraction '{}' document '{}' submitted — taskId='{}' fileId='{}'",
                    extractionId, documentId, response.taskId(), response.fileId());

            Map<String, String> externalIds = new HashMap<>(extractionRepository.getDocumentExternalIds(extractionId));
            externalIds.put(documentId, response.taskId());
            extractionRepository.updateDocumentExternalIds(extractionId, externalIds);

            if (response.fileId() != null && !response.fileId().isBlank()) {
                documentRepository.updateExternalFileId(documentId, response.fileId());
            }

            return true;
        } catch (ReductoClientException e) {
            log.error("[ExtractionWorker] Extraction '{}' document '{}' — Reducto submission failed: {}",
                    extractionId, documentId, e.getMessage(), e);
            return false;
        }
    }
}
