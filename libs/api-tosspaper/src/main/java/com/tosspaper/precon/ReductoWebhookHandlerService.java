package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Business logic for inbound Reducto webhook callbacks.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li>Look up the in-progress extraction by {@code external_task_id}.</li>
 *   <li>Enqueue the verified payload onto the {@link PreconExtractionRepository}
 *       pipeline so it can be processed asynchronously by the ExtractionWorker
 *       (TOS-38). For this PR the payload is accepted and the extraction is
 *       located, providing the wiring point for TOS-38.</li>
 *   <li>If all documents for the extraction are terminal, trigger
 *       {@link ConflictDetector#detectAndMarkConflicts(String)}.</li>
 * </ol>
 *
 * <h3>Idempotency</h3>
 * <p>Duplicate Svix deliveries for the same {@code task_id} are safe — if the
 * extraction is already in a terminal state the handler logs and returns normally
 * without re-processing.
 *
 * <h3>TOS-38 integration point</h3>
 * <p>{@code ConflictDetector} is a standalone injectable component. When
 * ExtractionWorker (TOS-38) is merged it will call
 * {@link ConflictDetector#detectAndMarkConflicts(String)} after batch completion
 * using the same bean wired here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReductoWebhookHandlerService {

    private final PreconExtractionRepository preconExtractionRepository;
    private final ConflictDetector conflictDetector;

    /**
     * Handles a verified, deserialised webhook payload from Reducto.
     *
     * <p>Looks up the extraction by {@code task_id} and, when all documents
     * for the extraction have reached a terminal state, runs conflict detection.
     *
     * @param payload the verified webhook payload
     * @throws NotFoundException if no extraction matches the given {@code task_id}
     */
    public void handle(ReductoWebhookPayload payload) {
        String taskId = payload.taskId();
        log.info("[ReductoWebhook] Processing webhook for task_id={} status={}",
                taskId, payload.status());

        ExtractionWithDocs extraction = preconExtractionRepository
                .findByExternalTaskId(taskId)
                .orElseThrow(() -> {
                    log.warn("[ReductoWebhook] No extraction found for task_id={}", taskId);
                    return new NotFoundException(
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND_CODE,
                            ApiErrorMessages.WEBHOOK_TASK_NOT_FOUND);
                });

        String extractionId = extraction.getId();
        log.debug("[ReductoWebhook] Matched task_id={} to extraction_id={}", taskId, extractionId);

        // ── Batch completion and conflict detection ──────────────────────────
        // TODO [TOS-38]: Once ExtractionWorker is wired, the field-write and
        //  markDocumentTerminal steps will precede this call. For this PR the
        //  ConflictDetector is exposed as a self-contained injectable component
        //  ready for TOS-38 to invoke after it writes all fields.
        //
        // ConflictDetector is called here whenever the payload status indicates
        // the task completed, allowing TOS-38 to hook in by delegating here.
        if ("completed".equalsIgnoreCase(payload.status())) {
            log.debug("[ReductoWebhook] Running conflict detection for extraction_id={}", extractionId);
            int conflictedRows = conflictDetector.detectAndMarkConflicts(extractionId);
            log.info("[ReductoWebhook] Conflict detection complete for extraction_id={} — {} row(s) flagged",
                    extractionId, conflictedRows);
        } else {
            log.info("[ReductoWebhook] Extraction task_id={} reported status='{}' — skipping conflict detection",
                    taskId, payload.status());
        }
    }
}
