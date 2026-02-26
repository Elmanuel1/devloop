package com.tosspaper.precon;

import com.tosspaper.common.NotFoundException;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static com.tosspaper.models.jooq.Tables.EXTRACTIONS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ExtractionRepositoryImpl implements ExtractionRepository {

    private final DSLContext dsl;

    @Override
    public ExtractionsRecord insert(ExtractionsRecord record) {
        log.info("Inserting extraction - id: {}, entityId: {}, entityType: {}",
                record.getId(), record.getEntityId(), record.getEntityType());
        return dsl.insertInto(EXTRACTIONS)
                .set(record)
                .returning()
                .fetchSingle();
    }

    @Override
    public ExtractionsRecord findById(String id) {
        return dsl.selectFrom(EXTRACTIONS)
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException(
                        ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                        ApiErrorMessages.EXTRACTION_NOT_FOUND));
    }

    @Override
    public List<ExtractionsRecord> findByEntityId(String companyId, String entityId, ExtractionQuery query) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(EXTRACTIONS.COMPANY_ID.eq(companyId));
        conditions.add(EXTRACTIONS.ENTITY_ID.eq(entityId));
        conditions.add(EXTRACTIONS.DELETED_AT.isNull());

        // Optional status filter
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            conditions.add(EXTRACTIONS.STATUS.eq(query.getStatus()));
        }

        // Cursor pagination (keyset on created_at DESC, id DESC)
        if (query.getCursorCreatedAt() != null && query.getCursorId() != null) {
            conditions.add(
                    DSL.row(EXTRACTIONS.CREATED_AT, EXTRACTIONS.ID)
                            .lessThan(DSL.row(query.getCursorCreatedAt(), query.getCursorId()))
            );
        }

        return dsl.selectFrom(EXTRACTIONS)
                .where(conditions)
                .orderBy(EXTRACTIONS.CREATED_AT.desc(), EXTRACTIONS.ID.desc())
                .limit(query.getLimit() + 1) // Fetch limit+1 to determine has_more
                .fetch();
    }

    @Override
    public int updateStatus(String id, String status) {
        log.info("Updating extraction status - id: {}, status: {}", id, status);
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, status)
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public int updateVersion(String id, int expectedVersion) {
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .and(EXTRACTIONS.VERSION.eq(expectedVersion))
                .execute();
    }

    @Override
    public int softDelete(String id) {
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.DELETED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .execute();
    }
}
