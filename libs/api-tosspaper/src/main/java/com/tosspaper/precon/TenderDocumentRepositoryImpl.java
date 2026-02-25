package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import com.tosspaper.common.NotFoundException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.tosspaper.models.jooq.Tables.TENDER_DOCUMENTS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TenderDocumentRepositoryImpl implements TenderDocumentRepository {

    private final DSLContext dsl;

    @Override
    public TenderDocumentsRecord insert(TenderDocumentsRecord record) {
        log.info("Inserting tender document - tenderId: {}, fileName: {}", record.getTenderId(), record.getFileName());
        return dsl.insertInto(TENDER_DOCUMENTS)
                .set(record)
                .returning()
                .fetchSingle();
    }

    @Override
    public TenderDocumentsRecord findById(String id) {
        return dsl.selectFrom(TENDER_DOCUMENTS)
                .where(TENDER_DOCUMENTS.ID.eq(id))
                .and(TENDER_DOCUMENTS.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException("api.tenderDocument.notFound", "Tender document not found"));
    }

    @Override
    public List<TenderDocumentsRecord> findByTenderId(String tenderId, String status, int limit,
                                                       OffsetDateTime cursorCreatedAt, String cursorId) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(TENDER_DOCUMENTS.TENDER_ID.eq(tenderId));
        conditions.add(TENDER_DOCUMENTS.DELETED_AT.isNull());

        // Status filter
        if (status != null && !status.isBlank()) {
            conditions.add(TENDER_DOCUMENTS.STATUS.eq(status));
        }

        // Cursor pagination using row comparison
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
}
