package com.tosspaper.precon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.NotFoundException;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.tosspaper.models.jooq.Tables.EXTRACTIONS;

/**
 * jOOQ-based implementation of {@link PreconExtractionRepository}.
 *
 * <p>{@code external_task_id} was added to the {@code extractions} table by
 * migration {@code V3.7__add_external_task_id_to_extractions.sql}. The
 * {@code flyway-jooq-classes} artifact is published separately and has not
 * yet been regenerated with this column, so it is accessed via
 * {@link DSL#field(String, Class)} as a typed bridge.
 * Replace with {@code EXTRACTIONS.EXTERNAL_TASK_ID} once the artifact is
 * republished and the version in {@code libs.versions.toml} is bumped.
 *
 * <p>{@link #findPendingExtractions(int)} reads the {@code document_ids} JSONB
 * column from each row and parses it into a {@code List<String>} inline,
 * returning {@link ExtractionWithDocs} so that callers need no second DB
 * round-trip.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PreconExtractionRepositoryImpl implements PreconExtractionRepository {

    private static final org.jooq.Field<String> EXTERNAL_TASK_ID =
            DSL.field("external_task_id", String.class);

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    public ExtractionsRecord findByExternalTaskId(String externalTaskId) {
        return dsl.selectFrom(EXTRACTIONS)
                .where(EXTERNAL_TASK_ID.eq(externalTaskId))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException(
                        ApiErrorMessages.EXTRACTION_NOT_FOUND_CODE,
                        ApiErrorMessages.EXTRACTION_NOT_FOUND));
    }

    @Override
    public List<ExtractionWithDocs> findPendingExtractions(int limit) {
        List<ExtractionsRecord> records = dsl.selectFrom(EXTRACTIONS)
                .where(EXTRACTIONS.STATUS.eq(ExtractionStatus.PENDING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .orderBy(EXTRACTIONS.CREATED_AT.asc())
                .limit(limit)
                .fetch();

        return records.stream()
                .map(record -> new ExtractionWithDocs(record, parseDocumentIds(record)))
                .toList();
    }

    @Override
    public int markAsProcessing(String id) {
        log.debug("[ExtractionPoll] Marking extraction {} as processing", id);
        // Guard: only transition from PENDING so a re-delivered poll message
        // on the same row is a safe no-op (idempotent).
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, ExtractionStatus.PROCESSING.getValue())
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.STATUS.eq(ExtractionStatus.PENDING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public int markAsCompleted(String id) {
        log.debug("[ExtractionPoll] Marking extraction {} as completed", id);
        return updateStatus(id, ExtractionStatus.COMPLETED.getValue());
    }

    @Override
    public int markAsFailed(String id) {
        log.debug("[ExtractionPoll] Marking extraction {} as failed", id);
        return updateStatus(id, ExtractionStatus.FAILED.getValue());
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Transitions an extraction to {@code status}, but only when the current
     * database status is {@code PROCESSING}.
     *
     * <p>This FROM-state guard mirrors the guard in {@link #markAsProcessing},
     * which only acts when status is {@code PENDING}. Without it,
     * {@code markAsCompleted} / {@code markAsFailed} would silently overwrite
     * any status — including {@code PENDING} — corrupting the audit trail.
     *
     * <p>A return value of {@code 0} means the row was either already in a
     * terminal state, deleted, or not found — the caller can treat that as an
     * idempotent no-op.
     */
    private int updateStatus(String id, String status) {
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, status)
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.STATUS.eq(ExtractionStatus.PROCESSING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .execute();
    }

    /**
     * Parses the {@code document_ids} JSONB column into a typed list.
     * Returns an empty list when the column is null or malformed — the
     * poll job will simply submit no documents for that extraction.
     */
    private List<String> parseDocumentIds(ExtractionsRecord record) {
        if (record.getDocumentIds() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    record.getDocumentIds().data(),
                    new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[ExtractionPoll] Failed to parse document_ids for extraction {}: {}",
                    record.getId(), e.getMessage());
            return List.of();
        }
    }
}
