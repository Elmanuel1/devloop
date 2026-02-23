package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TendersRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.tosspaper.models.jooq.Tables.TENDERS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TenderRepositoryImpl implements TenderRepository {

    private final DSLContext dsl;

    @Override
    public TendersRecord insert(String companyId, Map<String, Object> fields) {
        String id = UUID.randomUUID().toString();
        log.info("Inserting tender - companyId: {}, name: {}", companyId, fields.get("name"));

        var insertStep = dsl.insertInto(TENDERS)
                .set(TENDERS.ID, id)
                .set(TENDERS.COMPANY_ID, companyId)
                .set(TENDERS.NAME, (String) fields.get("name"))
                .set(TENDERS.STATUS, "draft")
                .set(TENDERS.CREATED_BY, (String) fields.getOrDefault("created_by", "system"));

        if (fields.containsKey("platform")) {
            insertStep = insertStep.set(TENDERS.PLATFORM, (String) fields.get("platform"));
        }
        if (fields.containsKey("currency")) {
            insertStep = insertStep.set(TENDERS.CURRENCY, (String) fields.get("currency"));
        }
        if (fields.containsKey("closing_date")) {
            insertStep = insertStep.set(TENDERS.CLOSING_DATE, (OffsetDateTime) fields.get("closing_date"));
        }
        if (fields.containsKey("delivery_method")) {
            insertStep = insertStep.set(TENDERS.DELIVERY_METHOD, (String) fields.get("delivery_method"));
        }
        if (fields.containsKey("bonds")) {
            insertStep = insertStep.set(TENDERS.BONDS, JSONB.jsonbOrNull((String) fields.get("bonds")));
        }
        if (fields.containsKey("conditions")) {
            insertStep = insertStep.set(TENDERS.CONDITIONS, JSONB.jsonbOrNull((String) fields.get("conditions")));
        }
        if (fields.containsKey("parties")) {
            insertStep = insertStep.set(TENDERS.PARTIES, JSONB.jsonbOrNull((String) fields.get("parties")));
        }
        if (fields.containsKey("location")) {
            insertStep = insertStep.set(TENDERS.LOCATION, JSONB.jsonbOrNull((String) fields.get("location")));
        }
        if (fields.containsKey("metadata")) {
            insertStep = insertStep.set(TENDERS.METADATA, JSONB.jsonbOrNull((String) fields.get("metadata")));
        }
        if (fields.containsKey("reference_number")) {
            insertStep = insertStep.set(TENDERS.REFERENCE_NUMBER, (String) fields.get("reference_number"));
        }
        if (fields.containsKey("scope_of_work")) {
            insertStep = insertStep.set(TENDERS.SCOPE_OF_WORK, (String) fields.get("scope_of_work"));
        }
        if (fields.containsKey("submission_method")) {
            insertStep = insertStep.set(TENDERS.SUBMISSION_METHOD, (String) fields.get("submission_method"));
        }
        if (fields.containsKey("submission_url")) {
            insertStep = insertStep.set(TENDERS.SUBMISSION_URL, (String) fields.get("submission_url"));
        }
        if (fields.containsKey("liquidated_damages")) {
            insertStep = insertStep.set(TENDERS.LIQUIDATED_DAMAGES, (String) fields.get("liquidated_damages"));
        }
        if (fields.containsKey("inquiry_deadline")) {
            insertStep = insertStep.set(TENDERS.INQUIRY_DEADLINE, (OffsetDateTime) fields.get("inquiry_deadline"));
        }

        return insertStep.returning().fetchSingle();
    }

    @Override
    public Optional<TendersRecord> findById(String id) {
        TendersRecord record = dsl.selectFrom(TENDERS)
                .where(TENDERS.ID.eq(id))
                .and(TENDERS.DELETED_AT.isNull())
                .fetchOne();
        return Optional.ofNullable(record);
    }

    @Override
    public List<TendersRecord> findByCompanyId(String companyId, TenderQuery query) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(TENDERS.COMPANY_ID.eq(companyId));
        conditions.add(TENDERS.DELETED_AT.isNull());

        // Status filter
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            conditions.add(TENDERS.STATUS.eq(query.getStatus()));
        }

        // Search (ILIKE on name and reference_number)
        if (query.getSearch() != null && !query.getSearch().isBlank()) {
            String searchPattern = "%" + query.getSearch().trim() + "%";
            conditions.add(
                    TENDERS.NAME.likeIgnoreCase(searchPattern)
                            .or(TENDERS.REFERENCE_NUMBER.likeIgnoreCase(searchPattern))
            );
        }

        // Cursor pagination
        if (query.getCursorCreatedAt() != null && query.getCursorId() != null) {
            conditions.add(
                    DSL.row(TENDERS.CREATED_AT, TENDERS.ID)
                            .lessThan(DSL.row(query.getCursorCreatedAt(), query.getCursorId()))
            );
        }

        // Sorting
        List<SortField<?>> orderBy = buildSortFields(query.getSortBy(), query.getSortDirection());

        return dsl.selectFrom(TENDERS)
                .where(conditions)
                .orderBy(orderBy)
                .limit(query.getLimit() + 1) // Fetch limit+1 to determine has_more
                .fetch();
    }

    @Override
    public int update(String id, Map<String, Object> fields, int expectedVersion) {
        var updateStep = dsl.update(TENDERS)
                .set(TENDERS.UPDATED_AT, DSL.currentOffsetDateTime());

        updateStep = updateStep.set(TENDERS.VERSION, TENDERS.VERSION.plus(1));

        if (fields.containsKey("name")) {
            updateStep = updateStep.set(TENDERS.NAME, (String) fields.get("name"));
        }
        if (fields.containsKey("platform")) {
            updateStep = updateStep.set(TENDERS.PLATFORM, (String) fields.get("platform"));
        }
        if (fields.containsKey("status")) {
            updateStep = updateStep.set(TENDERS.STATUS, (String) fields.get("status"));
        }
        if (fields.containsKey("currency")) {
            updateStep = updateStep.set(TENDERS.CURRENCY, (String) fields.get("currency"));
        }
        if (fields.containsKey("reference_number")) {
            updateStep = updateStep.set(TENDERS.REFERENCE_NUMBER, (String) fields.get("reference_number"));
        }
        if (fields.containsKey("location")) {
            updateStep = updateStep.set(TENDERS.LOCATION, JSONB.jsonbOrNull((String) fields.get("location")));
        }
        if (fields.containsKey("scope_of_work")) {
            updateStep = updateStep.set(TENDERS.SCOPE_OF_WORK, (String) fields.get("scope_of_work"));
        }
        if (fields.containsKey("delivery_method")) {
            updateStep = updateStep.set(TENDERS.DELIVERY_METHOD, (String) fields.get("delivery_method"));
        }
        if (fields.containsKey("closing_date")) {
            updateStep = updateStep.set(TENDERS.CLOSING_DATE, (OffsetDateTime) fields.get("closing_date"));
        }
        if (fields.containsKey("inquiry_deadline")) {
            updateStep = updateStep.set(TENDERS.INQUIRY_DEADLINE, (OffsetDateTime) fields.get("inquiry_deadline"));
        }
        if (fields.containsKey("submission_method")) {
            updateStep = updateStep.set(TENDERS.SUBMISSION_METHOD, (String) fields.get("submission_method"));
        }
        if (fields.containsKey("submission_url")) {
            updateStep = updateStep.set(TENDERS.SUBMISSION_URL, (String) fields.get("submission_url"));
        }
        if (fields.containsKey("bonds")) {
            updateStep = updateStep.set(TENDERS.BONDS, JSONB.jsonbOrNull((String) fields.get("bonds")));
        }
        if (fields.containsKey("conditions")) {
            updateStep = updateStep.set(TENDERS.CONDITIONS, JSONB.jsonbOrNull((String) fields.get("conditions")));
        }
        if (fields.containsKey("liquidated_damages")) {
            updateStep = updateStep.set(TENDERS.LIQUIDATED_DAMAGES, (String) fields.get("liquidated_damages"));
        }
        if (fields.containsKey("parties")) {
            updateStep = updateStep.set(TENDERS.PARTIES, JSONB.jsonbOrNull((String) fields.get("parties")));
        }
        if (fields.containsKey("metadata")) {
            updateStep = updateStep.set(TENDERS.METADATA, JSONB.jsonbOrNull((String) fields.get("metadata")));
        }

        return updateStep
                .where(TENDERS.ID.eq(id))
                .and(TENDERS.DELETED_AT.isNull())
                .and(TENDERS.VERSION.eq(expectedVersion))
                .execute();
    }

    @Override
    public int softDelete(String id) {
        return dsl.update(TENDERS)
                .set(TENDERS.DELETED_AT, DSL.currentOffsetDateTime())
                .where(TENDERS.ID.eq(id))
                .and(TENDERS.DELETED_AT.isNull())
                .execute();
    }

    private List<SortField<?>> buildSortFields(String sortBy, String sortDirection) {
        boolean asc = "asc".equalsIgnoreCase(sortDirection);

        if ("closing_date".equals(sortBy)) {
            if (asc) {
                return List.of(
                        TENDERS.CLOSING_DATE.asc().nullsLast(),
                        TENDERS.CREATED_AT.desc(),
                        TENDERS.ID.desc()
                );
            } else {
                return List.of(
                        TENDERS.CLOSING_DATE.desc().nullsLast(),
                        TENDERS.CREATED_AT.desc(),
                        TENDERS.ID.desc()
                );
            }
        } else if ("name".equals(sortBy)) {
            if (asc) {
                return List.of(TENDERS.NAME.asc(), TENDERS.CREATED_AT.desc(), TENDERS.ID.desc());
            } else {
                return List.of(TENDERS.NAME.desc(), TENDERS.CREATED_AT.desc(), TENDERS.ID.desc());
            }
        } else {
            // Default: created_at desc
            if (asc) {
                return List.of(TENDERS.CREATED_AT.asc(), TENDERS.ID.asc());
            } else {
                return List.of(TENDERS.CREATED_AT.desc(), TENDERS.ID.desc());
            }
        }
    }
}
