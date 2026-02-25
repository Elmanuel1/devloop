package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
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
    public TenderDocumentsRecord insert(String id, String tenderId, String companyId, String fileName,
                                         String contentType, long fileSize, String s3Key, String status) {
        log.info("Inserting tender document - id: {}, tenderId: {}, fileName: {}", id, tenderId, fileName);

        return dsl.insertInto(TENDER_DOCUMENTS)
                .set(TENDER_DOCUMENTS.ID, id)
                .set(TENDER_DOCUMENTS.TENDER_ID, tenderId)
                .set(TENDER_DOCUMENTS.COMPANY_ID, companyId)
                .set(TENDER_DOCUMENTS.FILE_NAME, fileName)
                .set(TENDER_DOCUMENTS.CONTENT_TYPE, contentType)
                .set(TENDER_DOCUMENTS.FILE_SIZE, fileSize)
                .set(TENDER_DOCUMENTS.S3_KEY, s3Key)
                .set(TENDER_DOCUMENTS.STATUS, status)
                .returning()
                .fetchSingle();
    }

    @Override
    public Optional<TenderDocumentsRecord> findById(String id) {
        return Optional.ofNullable(
                dsl.selectFrom(TENDER_DOCUMENTS)
                        .where(TENDER_DOCUMENTS.ID.eq(id))
                        .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                        .fetchOne()
        );
    }

    @Override
    public List<TenderDocumentsRecord> findByTenderId(String tenderId, String status, int limit,
                                                       String cursorCreatedAt, String cursorId) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(TENDER_DOCUMENTS.TENDER_ID.eq(tenderId));
        conditions.add(TENDER_DOCUMENTS.DELETED_AT.isNull());

        // Optional status filter
        if (status != null && !status.isBlank()) {
            conditions.add(TENDER_DOCUMENTS.STATUS.eq(status));
        }

        // Cursor pagination
        if (cursorCreatedAt != null && cursorId != null) {
            OffsetDateTime cursorTime = OffsetDateTime.parse(cursorCreatedAt);
            conditions.add(
                    DSL.row(TENDER_DOCUMENTS.CREATED_AT, TENDER_DOCUMENTS.ID)
                            .lessThan(DSL.row(cursorTime, cursorId))
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
                .set(TENDER_DOCUMENTS.STATUS, "ready")
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
                .set(TENDER_DOCUMENTS.STATUS, "failed")
                .set(TENDER_DOCUMENTS.ERROR_REASON, errorReason)
                .set(TENDER_DOCUMENTS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(TENDER_DOCUMENTS.ID.eq(id))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .execute();
    }

}
