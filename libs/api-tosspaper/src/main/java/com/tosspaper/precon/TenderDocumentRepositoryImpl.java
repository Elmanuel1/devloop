package com.tosspaper.precon;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import com.tosspaper.precon.generated.model.TenderDocumentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.TENDER_DOCUMENTS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TenderDocumentRepositoryImpl implements TenderDocumentRepository {

    private final DSLContext dsl;

    @Override
    public TenderDocumentsRecord insert(TenderDocumentsRecord record) {
        log.info("Inserting tender document - id: {}, tenderId: {}, fileName: {}",
                record.getId(), record.getTenderId(), record.getFileName());

        return dsl.insertInto(TENDER_DOCUMENTS)
                .set(TENDER_DOCUMENTS.ID, record.getId())
                .set(TENDER_DOCUMENTS.TENDER_ID, record.getTenderId())
                .set(TENDER_DOCUMENTS.COMPANY_ID, record.getCompanyId())
                .set(TENDER_DOCUMENTS.FILE_NAME, record.getFileName())
                .set(TENDER_DOCUMENTS.CONTENT_TYPE, record.getContentType())
                .set(TENDER_DOCUMENTS.FILE_SIZE, record.getFileSize())
                .set(TENDER_DOCUMENTS.S3_KEY, record.getS3Key())
                .set(TENDER_DOCUMENTS.STATUS, record.getStatus())
                .set(TENDER_DOCUMENTS.ERROR_REASON, record.getErrorReason())
                .set(TENDER_DOCUMENTS.UPLOADED_AT, record.getUploadedAt())
                .returning()
                .fetchSingle();
    }

    @Override
    public TenderDocumentsRecord findById(String id) {
        return dsl.selectFrom(TENDER_DOCUMENTS)
                .where(TENDER_DOCUMENTS.ID.eq(id))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException(
                        ApiErrorMessages.DOCUMENT_NOT_FOUND_CODE,
                        ApiErrorMessages.DOCUMENT_NOT_FOUND));
    }

    @Override
    public List<TenderDocumentsRecord> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(TENDER_DOCUMENTS)
                .where(TENDER_DOCUMENTS.ID.in(ids))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .fetch();
    }

    @Override
    public List<TenderDocumentsRecord> findByTenderId(String tenderId, String status, int limit,
                                                       OffsetDateTime cursorCreatedAt, String cursorId) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(TENDER_DOCUMENTS.TENDER_ID.eq(tenderId));
        conditions.add(TENDER_DOCUMENTS.DELETED_AT.isNull());

        // Optional status filter
        if (status != null && !status.isBlank()) {
            conditions.add(TENDER_DOCUMENTS.STATUS.eq(status));
        }

        // Cursor pagination
        if (cursorCreatedAt != null && cursorId != null) {
            conditions.add(
                    DSL.row(TENDER_DOCUMENTS.CREATED_AT, TENDER_DOCUMENTS.ID)
                            .lessThan(DSL.row(cursorCreatedAt, cursorId))
            );
        }

        return dsl.selectFrom(TENDER_DOCUMENTS)
                .where(conditions)
                .orderBy(TENDER_DOCUMENTS.CREATED_AT.desc(), TENDER_DOCUMENTS.ID.desc())
                .limit(limit + 1) // Fetch limit+1 to determine has_more
                .fetch();
    }

    @Override
    public int softDelete(String id) {
        return dsl.update(TENDER_DOCUMENTS)
                .set(TENDER_DOCUMENTS.DELETED_AT, DSL.currentOffsetDateTime())
                .where(TENDER_DOCUMENTS.ID.eq(id))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public int updateStatusToProcessing(String id) {
        log.info("Updating document status to processing - id: {}", id);
        return dsl.update(TENDER_DOCUMENTS)
                .set(TENDER_DOCUMENTS.STATUS, "processing")
                .set(TENDER_DOCUMENTS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(TENDER_DOCUMENTS.ID.eq(id))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public int updateStatusToReady(String id) {
        log.info("Updating document status to ready - id: {}", id);
        return dsl.update(TENDER_DOCUMENTS)
                .set(TENDER_DOCUMENTS.STATUS, TenderDocumentStatus.READY.getValue())
                .set(TENDER_DOCUMENTS.UPLOADED_AT, DSL.currentOffsetDateTime())
                .set(TENDER_DOCUMENTS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(TENDER_DOCUMENTS.ID.eq(id))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public int updateStatusToFailed(String id, String errorReason) {
        log.info("Updating document status to failed - id: {}, reason: {}", id, errorReason);
        return dsl.update(TENDER_DOCUMENTS)
                .set(TENDER_DOCUMENTS.STATUS, TenderDocumentStatus.FAILED.getValue())
                .set(TENDER_DOCUMENTS.ERROR_REASON, errorReason)
                .set(TENDER_DOCUMENTS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(TENDER_DOCUMENTS.ID.eq(id))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public Optional<TenderDocumentsRecord> findByExternalTaskId(String externalTaskId) {
        // Raw field bridge until jOOQ classes jar is regenerated with the EXTERNAL_TASK_ID column.
        return dsl.selectFrom(TENDER_DOCUMENTS)
                .where(DSL.field("external_task_id", String.class).eq(externalTaskId))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .fetchOptional();
    }

}
