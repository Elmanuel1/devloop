package com.tosspaper.precon;

import com.tosspaper.aiengine.client.reducto.ReductoClient;
import com.tosspaper.aiengine.client.reducto.dto.ReductoJobStatusResponse;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

/** Business logic for inbound Reducto webhook callbacks. Fetches job result via {@link ReductoClient} and updates extraction state. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReductoWebhookHandlerService {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ConflictDetector conflictDetector;
    private final ReductoClient reductoClient;

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

        if ("completed".equalsIgnoreCase(payload.status())) {
            handleCompleted(jobId, extractionId);
        } else if ("failed".equalsIgnoreCase(payload.status())) {
            handleFailed(jobId, extractionId);
        } else {
            log.info("[ReductoWebhook] Extraction job_id={} reported status='{}' — no action taken",
                    jobId, payload.status());
        }
    }

    // ── Private handlers ──────────────────────────────────────────────────────

    private void handleCompleted(String jobId, String extractionId) {
        log.debug("[ReductoWebhook] Fetching completed job result for job_id={}", jobId);
        ReductoJobStatusResponse jobResult = fetchJobStatus(jobId);

        // TODO [TOS-38]: wire jobResult.getRawResponse() into PipelineExtractionResult.
        PipelineExtractionResult result = new PipelineExtractionResult(extractionId, null);
        preconExtractionRepository.markAsCompleted(extractionId, result);
        log.info("[ReductoWebhook] Marked extraction_id={} as completed (raw_response length={})",
                extractionId, jobResult.getRawResponse() != null ? jobResult.getRawResponse().length() : 0);

        int conflictedRows = conflictDetector.detectAndMarkConflicts(extractionId);
        log.info("[ReductoWebhook] Conflict detection complete for extraction_id={} — {} row(s) flagged",
                extractionId, conflictedRows);
    }

    private void handleFailed(String jobId, String extractionId) {
        log.debug("[ReductoWebhook] Fetching failure reason for job_id={}", jobId);
        ReductoJobStatusResponse jobResult = fetchJobStatus(jobId);

        String reason = jobResult.getReason() != null ? jobResult.getReason() : "Reducto reported job as failed";
        preconExtractionRepository.markAsFailed(extractionId, reason);
        log.info("[ReductoWebhook] Marked extraction_id={} as failed — reason='{}'", extractionId, reason);
    }

    private ReductoJobStatusResponse fetchJobStatus(String jobId) {
        try {
            return reductoClient.getJobStatus(jobId);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to fetch job result from Reducto for job_id=%s: %s".formatted(jobId, e.getMessage()), e);
        }
    }
}
