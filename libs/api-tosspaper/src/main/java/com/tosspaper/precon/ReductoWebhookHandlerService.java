package com.tosspaper.precon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult;
import com.tosspaper.aiengine.service.ProcessingService;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Business logic for inbound Reducto webhook callbacks. Fetches job result via {@link ProcessingService} and updates extraction state. */
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

        ExtractionWithDocs extraction = preconExtractionRepository
                .findByExternalTaskId(jobId)
                .orElseThrow(() -> {
                    log.warn("[ReductoWebhook] No extraction found for job_id={}", jobId);
                    return new NotFoundException(
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND_CODE,
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND);
                });

        String extractionId = extraction.getId();
        log.debug("[ReductoWebhook] Matched job_id={} to extraction_id={}", jobId, extractionId);

        switch (payload.status().toLowerCase()) {
            case "completed" -> handleCompleted(jobId, extractionId);
            case "failed"    -> handleFailed(jobId, extractionId);
            default          -> log.info("[ReductoWebhook] Extraction job_id={} reported status='{}' — no action taken",
                                        jobId, payload.status());
        }
    }

    // ── Private handlers ──────────────────────────────────────────────────────

    private void handleCompleted(String jobId, String extractionId) {
        ExtractTaskResult jobResult = processingService.getExtractTask(jobId);

        JsonNode fields = parseRawResponse(jobResult.getRawResponse());
        PipelineExtractionResult result = new PipelineExtractionResult(extractionId, fields);
        preconExtractionRepository.markAsCompleted(extractionId, result);
        log.info("[ReductoWebhook] Marked extraction_id={} as completed", extractionId);
    }

    private void handleFailed(String jobId, String extractionId) {
        ExtractTaskResult jobResult = processingService.getExtractTask(jobId);

        String reason = jobResult.getError() != null ? jobResult.getError() : "Reducto reported job as failed";
        preconExtractionRepository.markAsFailed(extractionId, reason);
        log.info("[ReductoWebhook] Marked extraction_id={} as failed — reason='{}'", extractionId, reason);
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
