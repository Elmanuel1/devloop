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
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.tosspaper.models.jooq.Tables.EXTRACTIONS;

/**
 * jOOQ-based implementation of {@link PreconExtractionRepository}.
 *
 * <p>{@code external_task_id} was added to the {@code extractions} table by
 * migration {@code V3.8__add_external_task_id_to_extractions.sql}. The
 * {@code flyway-jooq-classes} artifact is published separately and has not
 * yet been regenerated with this column, so it is accessed via
 * {@link DSL#field(String, Class)} as a typed bridge.
 * Replace with {@code EXTRACTIONS.EXTERNAL_TASK_ID} once the artifact is
 * republished and the version in {@code libs.versions.toml} is bumped.
 *
 * <p>{@link #claimNextBatch(int)} uses a single CTE with
 * {@code FOR UPDATE SKIP LOCKED} to atomically claim rows, eliminating the
 * need for any application-level distributed lock. Both the select and the
 * update happen in one round-trip so concurrent poll threads in different JVM
 * instances never claim the same row.
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
    public List<ExtractionWithDocs> claimNextBatch(int limit) {
        // Single-statement claim: select PENDING rows with SKIP LOCKED, then
        // immediately update them to PROCESSING — all inside one CTE so no
        // concurrent caller can claim the same row.
        String sql = """
                WITH claimed AS (
                    SELECT id FROM extractions
                    WHERE status = ?
                    AND deleted_at IS NULL
                    ORDER BY created_at ASC
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE extractions e
                SET status     = ?,
                    version    = e.version + 1,
                    updated_at = now()
                FROM claimed
                WHERE e.id = claimed.id
                RETURNING e.*
                """;

        List<Record> rows = dsl.fetch(sql,
                ExtractionStatus.PENDING.getValue(),
                limit,
                ExtractionStatus.PROCESSING.getValue());

        return rows.stream()
                .map(row -> {
                    ExtractionsRecord record = row.into(ExtractionsRecord.class);
                    return new ExtractionWithDocs(record, parseDocumentIds(record));
                })
                .toList();
    }

    @Override
    public int reapStaleExtractions(int staleMinutes) {
        log.debug("[ExtractionPoll] Reaping stale PROCESSING extractions older than {} minutes", staleMinutes);
        // Use a parameterised interval expression to avoid SQL injection.
        // The cast to ::integer is safe because staleMinutes is a typed int parameter, not a string.
        String sql = """
                UPDATE extractions
                SET status     = ?,
                    version    = version + 1,
                    updated_at = now()
                WHERE status     = ?
                AND   deleted_at IS NULL
                AND   updated_at < now() - (? * interval '1 minute')
                """;
        return dsl.execute(sql,
                ExtractionStatus.PENDING.getValue(),
                ExtractionStatus.PROCESSING.getValue(),
                staleMinutes);
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
