package com.tosspaper.aiengine.repository.impl;

import com.tosspaper.models.domain.ConformanceStatus;
import com.tosspaper.models.domain.ExtractionStatus;
import com.tosspaper.models.domain.ExtractionTask;
import com.tosspaper.models.domain.MatchType;
import com.tosspaper.models.jooq.tables.records.ExtractionTaskRecord;
import com.tosspaper.aiengine.repository.ExtractionTaskRepository;
import com.tosspaper.models.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.EXTRACTION_TASK;

/**
 * JOOQ implementation of ExtractionTaskRepository.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JooqExtractionTaskRepositoryImpl implements ExtractionTaskRepository {

    private final DSLContext dsl;

    @Override
    public ExtractionTask save(ExtractionTask extractionTask) {
        log.debug("Saving/updating extraction task for assigned ID: {}", extractionTask.getAssignedId());

        // Use PostgreSQL's ON CONFLICT to handle upsert
        ExtractionTaskRecord record = dsl.insertInto(EXTRACTION_TASK)
            .set(EXTRACTION_TASK.ASSIGNED_ID, extractionTask.getAssignedId())
            .set(EXTRACTION_TASK.COMPANY_ID, extractionTask.getCompanyId())
            .set(EXTRACTION_TASK.STORAGE_KEY, extractionTask.getStorageKey())
            .set(EXTRACTION_TASK.FROM_ADDRESS, extractionTask.getFromAddress())
            .set(EXTRACTION_TASK.TO_ADDRESS, extractionTask.getToAddress())
            .set(EXTRACTION_TASK.EMAIL_SUBJECT, extractionTask.getEmailSubject())
            .set(EXTRACTION_TASK.EMAIL_MESSAGE_ID, extractionTask.getEmailMessageId())
            .set(EXTRACTION_TASK.EMAIL_THREAD_ID, extractionTask.getEmailThreadId())
            .set(EXTRACTION_TASK.RECEIVED_AT, extractionTask.getReceivedAt())
            .set(EXTRACTION_TASK.CREATED_AT, extractionTask.getCreatedAt())
            .set(EXTRACTION_TASK.UPDATED_AT, OffsetDateTime.now())
            .onConflict(EXTRACTION_TASK.ASSIGNED_ID)
            .doUpdate()
            .set(EXTRACTION_TASK.ATTEMPTS, EXTRACTION_TASK.ATTEMPTS.plus(1))
            .set(EXTRACTION_TASK.EMAIL_SUBJECT, extractionTask.getEmailSubject())
            .set(EXTRACTION_TASK.EMAIL_MESSAGE_ID, extractionTask.getEmailMessageId())
            .set(EXTRACTION_TASK.EMAIL_THREAD_ID, extractionTask.getEmailThreadId())
            .set(EXTRACTION_TASK.RECEIVED_AT, extractionTask.getReceivedAt())
            .set(EXTRACTION_TASK.UPDATED_AT, OffsetDateTime.now())
            .returning()
            .fetchOne();
        
        return mapRecordToDomain(record);
    }




    @Override
    public ExtractionTask update(ExtractionTask extractionTask, ExtractionStatus expectedStatus) {
        return update(dsl,extractionTask, expectedStatus );
    }

    @Override
    public ExtractionTask update(DSLContext dslContext, ExtractionTask extractionTask, ExtractionStatus expectedStatus) {
        log.debug("Updating extraction task: {} with expected status: {}", extractionTask.getAssignedId(), expectedStatus);

        int updated = dslContext.update(EXTRACTION_TASK)
                .set(EXTRACTION_TASK.STATUS, extractionTask.getStatus().getDisplayName())
                .set(EXTRACTION_TASK.PREPARATION_ID, extractionTask.getPreparationId())
                .set(EXTRACTION_TASK.TASK_ID, extractionTask.getTaskId())
                .set(EXTRACTION_TASK.ERROR_MESSAGE, extractionTask.getErrorMessage())
                .set(EXTRACTION_TASK.PREPARATION_STARTED_AT, extractionTask.getPreparationStartedAt())
                .set(EXTRACTION_TASK.EXTRACTION_STARTED_AT, extractionTask.getExtractionStartedAt())
                .set(EXTRACTION_TASK.DOCUMENT_TYPE, extractionTask.getDocumentType() != null ? extractionTask.getDocumentType().getFilePrefix() : null)
                .set(EXTRACTION_TASK.EXTRACT_TASK_RESULTS, JSONB.jsonbOrNull(extractionTask.getExtractTaskResults()))
                .set(EXTRACTION_TASK.CONFORMED_JSON, JSONB.jsonbOrNull(extractionTask.getConformedJson()))
                .set(EXTRACTION_TASK.CONFORMANCE_SCORE,
                        extractionTask.getConformanceScore() != null ?
                                java.math.BigDecimal.valueOf(extractionTask.getConformanceScore()) : null)
                .set(EXTRACTION_TASK.CONFORMANCE_STATUS, extractionTask.getConformanceStatus() != null ? extractionTask.getConformanceStatus().name() : null)
                .set(EXTRACTION_TASK.CONFORMANCE_ATTEMPTS, extractionTask.getConformanceAttempts())
                .set(EXTRACTION_TASK.CONFORMANCE_HISTORY, JSONB.jsonbOrNull(extractionTask.getConformanceHistory()))
                .set(EXTRACTION_TASK.CONFORMANCE_EVALUATION, JSONB.jsonbOrNull(extractionTask.getConformanceEvaluation()))
                .set(EXTRACTION_TASK.CONFORMED_AT, extractionTask.getConformedAt())
                .set(EXTRACTION_TASK.UPDATED_AT, OffsetDateTime.now())
                .set(EXTRACTION_TASK.PO_NUMBER, extractionTask.getPoNumber())
                .set(EXTRACTION_TASK.PROJECT_ID, extractionTask.getProjectId())
                .set(EXTRACTION_TASK.MATCH_TYPE, extractionTask.getMatchType().getValue())
                .set(EXTRACTION_TASK.PURCHASE_ORDER_ID, extractionTask.getPurchaseOrderId())
                .where(EXTRACTION_TASK.ASSIGNED_ID.eq(extractionTask.getAssignedId())
                        .and(EXTRACTION_TASK.STATUS.eq(expectedStatus.getDisplayName())))
                .execute();

        if (updated == 0) {
            throw new RuntimeException("Extraction task not found or status mismatch: " + extractionTask.getAssignedId() +
                    " (expected status: " + expectedStatus.getDisplayName() + ")");
        }

        return extractionTask;
    }

    @Override
    public Optional<ExtractionTask> findByTaskId(String taskId) {
        log.debug("Finding extraction task by task ID: {}", taskId);

        return dsl.selectFrom(EXTRACTION_TASK)
            .where(EXTRACTION_TASK.TASK_ID.eq(taskId))
            .fetchOptional()
            .map(this::mapRecordToDomain);
    }
    
    @Override
    public ExtractionTask findByAssignedId(String assignedId) {        
        return dsl.selectFrom(EXTRACTION_TASK)
            .where(EXTRACTION_TASK.ASSIGNED_ID.eq(assignedId))
            .fetchOptional()
            .map(this::mapRecordToDomain)
            .orElseThrow(() -> new NotFoundException("ExtractionTask not found with assigned ID: " + assignedId));
    }

    @Override
    public void updateManualPoInformation(ExtractionTask extractionTask) {
        dsl.update(EXTRACTION_TASK)
                .set(EXTRACTION_TASK.PO_NUMBER, extractionTask.getPoNumber())
                .set(EXTRACTION_TASK.PROJECT_ID, extractionTask.getProjectId())
                .set(EXTRACTION_TASK.MATCH_TYPE, extractionTask.getMatchType().getValue())
                .set(EXTRACTION_TASK.PURCHASE_ORDER_ID, extractionTask.getPurchaseOrderId())
                .set(EXTRACTION_TASK.MATCH_TYPE, MatchType.MANUAL.getValue())
                .where(EXTRACTION_TASK.ASSIGNED_ID.eq(extractionTask.getAssignedId()))
                .execute();
    }


    private ExtractionTask mapRecordToDomain(ExtractionTaskRecord record) {
        return ExtractionTask.builder()
            .assignedId(record.getAssignedId())
            .companyId(record.getCompanyId())
            .storageKey(record.getStorageKey())
            .status(ExtractionStatus.fromDisplayName(record.getStatus()))
            .preparationId(record.getPreparationId())
            .taskId(record.getTaskId())
            .errorMessage(record.getErrorMessage())
            .attempts(record.getAttempts())
            .createdAt(record.getCreatedAt())
            .updatedAt(record.getUpdatedAt())
            .preparationStartedAt(record.getPreparationStartedAt())
            .extractionStartedAt(record.getExtractionStartedAt())
            .documentType(record.getDocumentType() != null ? 
                com.tosspaper.models.domain.DocumentType.fromString(record.getDocumentType()) : null)
            .extractTaskResults(record.getExtractTaskResults() != null ? record.getExtractTaskResults().data() : null)
            .conformedJson(record.getConformedJson() != null ? record.getConformedJson().data() : null)
            .conformanceScore(record.getConformanceScore() != null ? record.getConformanceScore().doubleValue() : null)
            .conformanceStatus(record.getConformanceStatus() != null ? 
                ConformanceStatus.fromString(record.getConformanceStatus()) : null)
            .conformanceAttempts(record.getConformanceAttempts())
            .conformanceHistory(record.getConformanceHistory() != null ? record.getConformanceHistory().data() : null)
            .conformanceEvaluation(record.getConformanceEvaluation() != null ? record.getConformanceEvaluation().data() : null)
            .conformedAt(record.getConformedAt())
            .fromAddress(record.getFromAddress())
            .toAddress(record.getToAddress())
            .emailSubject(record.getEmailSubject())
            .emailMessageId(record.getEmailMessageId())
            .emailThreadId(record.getEmailThreadId())
            .receivedAt(record.getReceivedAt())
            .matchType(MatchType.fromValue(record.getMatchType()))
            .matchReport(record.getMatchReport() != null ? record.getMatchReport().data() : null)
            .reviewStatus(record.getReviewStatus())
            .projectId(record.getProjectId())
            .purchaseOrderId(record.getPurchaseOrderId())
            .poNumber(record.getPoNumber())
            .build();
    }
}
