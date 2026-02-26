package com.tosspaper.precon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.tosspaper.models.jooq.Tables.EXTRACTION_FIELDS;
import static com.tosspaper.models.jooq.Tables.EXTRACTIONS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ExtractionFieldRepositoryImpl implements ExtractionFieldRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExtractionFieldsRecord> findByExtractionId(ExtractionFieldQuery query) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(EXTRACTION_FIELDS.EXTRACTION_ID.eq(query.getExtractionId()));

        // Optional field_name filter
        if (query.getFieldName() != null && !query.getFieldName().isBlank()) {
            conditions.add(EXTRACTION_FIELDS.FIELD_NAME.eq(query.getFieldName()));
        }

        // Filter by document_id using PostgreSQL's JSONB containment operator (@>).
        //
        // The `citations` column stores a JSONB array of Citation objects, e.g.:
        //   [{"document_id": "abc", "page": 3}, {"document_id": "xyz", "page": 7}]
        //
        // The @> operator checks whether the left operand contains the right operand.
        // We build the right-hand filter as: [{"document_id": "<targetId>"}]
        // PostgreSQL matches any row whose citations array contains at least one
        // element with that document_id, regardless of other fields in the object.
        if (query.getDocumentId() != null && !query.getDocumentId().isBlank()) {
            String containsJson = serializeCitationFilter(query.getDocumentId());
            conditions.add(
                    DSL.condition(
                            "{0} @> {1}::jsonb",
                            EXTRACTION_FIELDS.CITATIONS,
                            DSL.inline(containsJson)
                    )
            );
        }

        // Cursor pagination
        if (query.getCursorCreatedAt() != null && query.getCursorId() != null) {
            conditions.add(
                    DSL.row(EXTRACTION_FIELDS.CREATED_AT, EXTRACTION_FIELDS.ID)
                            .lessThan(DSL.row(query.getCursorCreatedAt(), query.getCursorId()))
            );
        }

        return dsl.selectFrom(EXTRACTION_FIELDS)
                .where(conditions)
                .orderBy(EXTRACTION_FIELDS.CREATED_AT.desc(), EXTRACTION_FIELDS.ID.desc())
                .limit(query.getLimit() + 1) // Fetch limit+1 to determine has_more
                .fetch();
    }

    @Override
    public ExtractionFieldsRecord findById(String id) {
        return dsl.selectFrom(EXTRACTION_FIELDS)
                .where(EXTRACTION_FIELDS.ID.eq(id))
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException(
                        ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND_CODE,
                        ApiErrorMessages.EXTRACTION_FIELD_NOT_FOUND));
    }

    @Override
    public List<ExtractionFieldsRecord> findAllByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(EXTRACTION_FIELDS)
                .where(EXTRACTION_FIELDS.ID.in(ids))
                .fetch();
    }

    @Override
    public int updateEditedValue(String id, JSONB editedValue) {
        return dsl.update(EXTRACTION_FIELDS)
                .set(EXTRACTION_FIELDS.EDITED_VALUE, editedValue)
                .set(EXTRACTION_FIELDS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTION_FIELDS.ID.eq(id))
                .execute();
    }

    @Override
    public int deleteByExtractionId(String extractionId) {
        log.info("Deleting extraction fields for extraction - id: {}", extractionId);
        return dsl.deleteFrom(EXTRACTION_FIELDS)
                .where(EXTRACTION_FIELDS.EXTRACTION_ID.eq(extractionId))
                .execute();
    }

    @Override
    public BulkUpdateResult bulkUpdateEditedValues(List<FieldEditUpdate> updates,
                                                    String extractionId,
                                                    int expectedVersion) {
        List<ExtractionFieldsRecord> updatedRecords = new ArrayList<>();
        for (FieldEditUpdate update : updates) {
            ExtractionFieldsRecord updated = dsl.update(EXTRACTION_FIELDS)
                    .set(EXTRACTION_FIELDS.EDITED_VALUE, update.editedValue())
                    .set(EXTRACTION_FIELDS.UPDATED_AT, DSL.currentOffsetDateTime())
                    .where(EXTRACTION_FIELDS.ID.eq(update.fieldId()))
                    .returning()
                    .fetchSingle();
            updatedRecords.add(updated);
        }

        int rowsUpdated = dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(extractionId))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .and(EXTRACTIONS.VERSION.eq(expectedVersion))
                .execute();

        return new BulkUpdateResult(updatedRecords, rowsUpdated);
    }

    private String serializeCitationFilter(String documentId) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of("document_id", documentId)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(ApiErrorMessages.SERIALIZATION_ERROR, e);
        }
    }
}
