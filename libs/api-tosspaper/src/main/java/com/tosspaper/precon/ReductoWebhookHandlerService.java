package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult;
import com.tosspaper.aiengine.service.ProcessingService;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Business logic for inbound Reducto webhook callbacks. Fetches job result via {@link ProcessingService} and stores per-document fields. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReductoWebhookHandlerService {

    private final TenderDocumentRepository tenderDocumentRepository;
    private final ProcessingService processingService;
    private final ObjectMapper objectMapper;

    public void handle(ReductoWebhookPayload payload) {
        String jobId = payload.jobId();
        log.info("[ReductoWebhook] Processing webhook for job_id={} status={}",
                jobId, payload.status());

        // Find the document that owns this Reducto job — throws NotFoundException if not found.
        TenderDocumentsRecord document = tenderDocumentRepository
                .findByExternalTaskId(jobId)
                .orElseThrow(() -> {
                    log.warn("[ReductoWebhook] No document found for job_id={}", jobId);
                    return new NotFoundException(
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND_CODE,
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND);
                });

        // Each webhook fires per-document. Do not finalise the extraction here —
        // that is the responsibility of ExtractionWorker (TOS-38) once all
        // documents in the batch have reported in.
        switch (payload.status().toLowerCase()) {
            case "completed" -> handleCompleted(jobId, document);
            case "failed"    -> handleFailed(jobId, document);
            default          -> log.info("[ReductoWebhook] job_id={} reported status='{}' — no action taken",
                                        jobId, payload.status());
        }
    }

    // ── Private handlers ──────────────────────────────────────────────────────

    private void handleCompleted(String jobId, TenderDocumentsRecord document) {
        ExtractTaskResult jobResult = processingService.getExtractTask(jobId);
        JsonNode fields = parseRawResponse(jobResult.getRawResponse());
        // TODO [TOS-38]: persist fields to extraction_fields table via ExtractionFieldRepository.
        log.info("[ReductoWebhook] job_id={} document_id={} completed — fields ready for TOS-38 persistence (size={})",
                jobId, document.getId(), fields.size());
    }

    private void handleFailed(String jobId, TenderDocumentsRecord document) {
        ExtractTaskResult jobResult = processingService.getExtractTask(jobId);
        String reason = jobResult.getError() != null ? jobResult.getError() : "Reducto reported job as failed";
        // TODO [TOS-38]: record per-document failure via ExtractionFieldRepository or document status table.
        log.warn("[ReductoWebhook] job_id={} document_id={} failed — reason='{}'", jobId, document.getId(), reason);
    }

    private JsonNode parseRawResponse(String rawResponse) {
        if (rawResponse == null) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(rawResponse);
        } catch (Exception e) {
            log.warn("[ReductoWebhook] Could not parse raw_response as JSON — storing as text node");
            return objectMapper.valueToTree(rawResponse);
        }
    }
}
