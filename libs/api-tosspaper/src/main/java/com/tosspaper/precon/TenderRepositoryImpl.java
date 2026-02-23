package com.tosspaper.precon;

import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.TendersRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

import static com.tosspaper.models.jooq.Tables.TENDERS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class TenderRepositoryImpl implements TenderRepository {

    private final DSLContext dsl;

    @Override
    public TendersRecord insert(TendersRecord record) {
        log.info("Inserting tender - companyId: {}, name: {}", record.getCompanyId(), record.getName());
        return dsl.insertInto(TENDERS)
                .set(record)
                .returning()
                .fetchSingle();
    }

    @Override
    public TendersRecord findById(String id) {
        return dsl.selectFrom(TENDERS)
                .where(TENDERS.ID.eq(id))
                .and(TENDERS.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException("api.tender.notFound", "Tender not found"));
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
    public int update(String id, TendersRecord record, int expectedVersion) {
        var updateStep = dsl.update(TENDERS)
                .set(TENDERS.UPDATED_AT, DSL.currentOffsetDateTime())
                .set(TENDERS.VERSION, TENDERS.VERSION.plus(1));

        // Only update fields that were changed by MapStruct
        if (record.changed(TENDERS.NAME)) {
            updateStep = updateStep.set(TENDERS.NAME, record.getName());
        }
        if (record.changed(TENDERS.PLATFORM)) {
            updateStep = updateStep.set(TENDERS.PLATFORM, record.getPlatform());
        }
        if (record.changed(TENDERS.STATUS)) {
            updateStep = updateStep.set(TENDERS.STATUS, record.getStatus());
        }
        if (record.changed(TENDERS.CURRENCY)) {
            updateStep = updateStep.set(TENDERS.CURRENCY, record.getCurrency());
        }
        if (record.changed(TENDERS.REFERENCE_NUMBER)) {
            updateStep = updateStep.set(TENDERS.REFERENCE_NUMBER, record.getReferenceNumber());
        }
        if (record.changed(TENDERS.SCOPE_OF_WORK)) {
            updateStep = updateStep.set(TENDERS.SCOPE_OF_WORK, record.getScopeOfWork());
        }
        if (record.changed(TENDERS.DELIVERY_METHOD)) {
            updateStep = updateStep.set(TENDERS.DELIVERY_METHOD, record.getDeliveryMethod());
        }
        if (record.changed(TENDERS.CLOSING_DATE)) {
            updateStep = updateStep.set(TENDERS.CLOSING_DATE, record.getClosingDate());
        }
        if (record.changed(TENDERS.INQUIRY_DEADLINE)) {
            updateStep = updateStep.set(TENDERS.INQUIRY_DEADLINE, record.getInquiryDeadline());
        }
        if (record.changed(TENDERS.SUBMISSION_METHOD)) {
            updateStep = updateStep.set(TENDERS.SUBMISSION_METHOD, record.getSubmissionMethod());
        }
        if (record.changed(TENDERS.SUBMISSION_URL)) {
            updateStep = updateStep.set(TENDERS.SUBMISSION_URL, record.getSubmissionUrl());
        }
        if (record.changed(TENDERS.BONDS)) {
            updateStep = updateStep.set(TENDERS.BONDS, record.getBonds());
        }
        if (record.changed(TENDERS.CONDITIONS)) {
            updateStep = updateStep.set(TENDERS.CONDITIONS, record.getConditions());
        }
        if (record.changed(TENDERS.LIQUIDATED_DAMAGES)) {
            updateStep = updateStep.set(TENDERS.LIQUIDATED_DAMAGES, record.getLiquidatedDamages());
        }
        if (record.changed(TENDERS.PARTIES)) {
            updateStep = updateStep.set(TENDERS.PARTIES, record.getParties());
        }
        if (record.changed(TENDERS.METADATA)) {
            updateStep = updateStep.set(TENDERS.METADATA, record.getMetadata());
        }
        if (record.changed(TENDERS.LOCATION)) {
            updateStep = updateStep.set(TENDERS.LOCATION, record.getLocation());
        }
        if (record.changed(TENDERS.START_DATE)) {
            updateStep = updateStep.set(TENDERS.START_DATE, record.getStartDate());
        }
        if (record.changed(TENDERS.COMPLETION_DATE)) {
            updateStep = updateStep.set(TENDERS.COMPLETION_DATE, record.getCompletionDate());
        }
        if (record.changed(TENDERS.EVENTS)) {
            updateStep = updateStep.set(TENDERS.EVENTS, record.getEvents());
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
