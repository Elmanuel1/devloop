package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult;
import com.tosspaper.aiengine.service.ProcessingService;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Business logic for inbound Reducto webhook callbacks. Fetches job result via {@link ProcessingService} and stores per-document fields. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReductoWebhookHandlerService {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ProcessingService processingService;
    private final ObjectMapper objectMapper;

    public void handle(ReductoWebhookPayload payload) {
        String jobId = payload.jobId();
        log.info("[ReductoWebhook] Processing webhook for job_id={} status={}",
                jobId, payload.status());

        // Find the extraction that owns this Reducto job — throws NotFoundException if not found.
        ExtractionsRecord extraction = preconExtractionRepository
                .findByDocumentExternalTaskId(jobId)
                .orElseThrow(() -> {
                    log.warn("[ReductoWebhook] No extraction found for job_id={}", jobId);
                    return new NotFoundException(
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND_CODE,
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND);
                });

        // Each webhook fires per-document. Do not finalise the extraction here —
        // that is the responsibility of ExtractionWorker (TOS-38) once all
        // documents in the batch have reported in.
        switch (payload.status().toLowerCase()) {
            case "completed" -> handleCompleted(jobId, extraction);
            case "failed"    -> handleFailed(jobId, extraction);
            default          -> log.info("[ReductoWebhook] job_id={} reported status='{}' — no action taken",
                                        jobId, payload.status());
        }
    }

    // ── Private handlers ──────────────────────────────────────────────────────

    @SneakyThrows
    private void handleCompleted(String jobId, ExtractionsRecord extraction) {
        ExtractTaskResult jobResult = processingService.getExtractTask(jobId);
        JsonNode fields = objectMapper.readTree(jobResult.getRawResponse());
        // TODO [TOS-38]: persist fields to extraction_fields table via ExtractionFieldRepository.
        log.info("[ReductoWebhook] job_id={} extraction_id={} completed — fields ready for TOS-38 persistence (size={})",
                jobId, extraction.getId(), fields.size());
    }

    private void handleFailed(String jobId, ExtractionsRecord extraction) {
        ExtractTaskResult jobResult = processingService.getExtractTask(jobId);
        String reason = jobResult.getError() != null ? jobResult.getError() : "Reducto reported job as failed";
        // TODO [TOS-38]: record per-document failure via ExtractionFieldRepository or document status table.
        log.warn("[ReductoWebhook] job_id={} extraction_id={} failed — reason='{}'", jobId, extraction.getId(), reason);
    }
}
