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

import java.time.OffsetDateTime;
import java.util.List;

import static com.tosspaper.models.jooq.Tables.EXTRACTIONS;

/**
 * jOOQ-based implementation of {@link PreconExtractionRepository}.
 *
 * <p>{@code external_task_id} was added by migration {@code V3.8}. The
 * {@code flyway-jooq-classes} artifact has not been regenerated yet, so
 * this column is accessed via {@link DSL#field(String, Class)} as a typed
 * bridge until the artifact is republished.
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
        // Subquery: lock the oldest PENDING rows — SKIP LOCKED prevents concurrent
        // poll threads from claiming the same row.
        var claimIds = dsl.select(EXTRACTIONS.ID)
                .from(EXTRACTIONS)
                .where(EXTRACTIONS.STATUS.eq(ExtractionStatus.PENDING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .orderBy(EXTRACTIONS.CREATED_AT.asc())
                .limit(limit)
                .forUpdate().skipLocked();

        // Atomic UPDATE: transition claimed rows to PROCESSING and return full records.
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, ExtractionStatus.PROCESSING.getValue())
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.in(claimIds))
                .returning()
                .fetch()
                .map(record -> {
                    ExtractionsRecord extractionsRecord = record.into(ExtractionsRecord.class);
                    return new ExtractionWithDocs(extractionsRecord, parseDocumentIds(extractionsRecord));
                });
    }

    @Override
    public int reapStaleExtractions(int staleMinutes) {
        log.debug("[ExtractionPoll] Reaping stale PROCESSING extractions older than {} minutes", staleMinutes);

        OffsetDateTime staleThreshold = OffsetDateTime.now().minusMinutes(staleMinutes);

        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, ExtractionStatus.PENDING.getValue())
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.STATUS.eq(ExtractionStatus.PROCESSING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .and(EXTRACTIONS.UPDATED_AT.lt(staleThreshold))
                .execute();
    }

    @Override
    public int markAsCompleted(String id, PipelineExtractionResult result) {
        log.debug("[ExtractionPoll] Marking extraction {} as completed", id);
        // TODO [TOS-38] Persist result.fields() to extraction_fields table once
        //  the Reducto field schema is finalised. For now only the status is updated.
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, ExtractionStatus.COMPLETED.getValue())
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.STATUS.eq(ExtractionStatus.PROCESSING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public int markAsFailed(String id, String errorReason) {
        log.debug("[ExtractionPoll] Marking extraction {} as failed: {}", id, errorReason);
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, ExtractionStatus.FAILED.getValue())
                .set(EXTRACTIONS.ERROR_REASON, errorReason)
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.STATUS.eq(ExtractionStatus.PROCESSING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .execute();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Parses the {@code document_ids} JSONB column into a typed list.
     * Returns an empty list when the column is null or malformed.
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
