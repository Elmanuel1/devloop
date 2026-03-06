package com.tosspaper.precon;

import com.tosspaper.models.exception.ReductoClientException;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.models.precon.ConstructionDocumentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Orchestrates the classify → upload → extract pipeline for a single document. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractionWorker {

    private final DocumentClassifier documentClassifier;
    private final ExtractionClient extractionClient;
    private final TenderDocumentRepository documentRepository;
    private final PreconExtractionRepository extractionRepository;
    private final DocumentContentReader contentReader;
    private final ReductoProperties reductoProperties;

    /** Processes one document within an extraction — classify, submit, record external IDs. */
    public boolean process(ExtractionWithDocs extraction, TenderDocumentsRecord document) {
        String extractionId = extraction.getId();
        String documentId = document.getId();

        byte[] contentBytes = contentReader.read(document.getS3Key());

        ConstructionDocumentType documentType = documentClassifier.classify(documentId, contentBytes);
        if (documentType == ConstructionDocumentType.UNKNOWN) {
            log.warn("[ExtractionWorker] Extraction '{}' document '{}' — UNKNOWN type, skipping",
                    extractionId, documentId);
            return true;
        }

        return submitToReducto(extraction, document, contentBytes, documentType);
    }

    private boolean submitToReducto(ExtractionWithDocs extraction, TenderDocumentsRecord document,
                                    byte[] fileBytes, ConstructionDocumentType documentType) {
        String extractionId = extraction.getId();
        String documentId = document.getId();
        String externalFileId = document.get("external_file_id", String.class);

        try {
            ExtractionSubmitRequest request = new ExtractionSubmitRequest(
                    extractionId,
                    documentId,
                    document.getS3Key(),
                    fileBytes,
                    reductoProperties.buildWebhookUrl(),
                    documentType,
                    externalFileId
            );
            ExtractionSubmitResponse response = extractionClient.submit(request);
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
