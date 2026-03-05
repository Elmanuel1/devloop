package com.tosspaper.precon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;
import com.tosspaper.precon.generated.model.ExtractionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.EXTRACTIONS;

/**
 * jOOQ-based implementation of {@link PreconExtractionRepository}.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PreconExtractionRepositoryImpl implements PreconExtractionRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

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
                .set(EXTRACTIONS.UPDATED_AT, OffsetDateTime.now())
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

        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, ExtractionStatus.PENDING.getValue())
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, OffsetDateTime.now())
                .where(EXTRACTIONS.STATUS.eq(ExtractionStatus.PROCESSING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .and(EXTRACTIONS.UPDATED_AT.lt(
                        DSL.field("CURRENT_TIMESTAMP - ({0} * interval '1 minute')",
                                OffsetDateTime.class, DSL.val(staleMinutes))))
                .execute();
    }

    @Override
    public int markAsCompleted(String id, PipelineExtractionResult ignoredResult) {
        log.debug("[ExtractionPoll] Marking extraction {} as completed", id);
        // TODO [TOS-38] Persist result.fields() to extraction_fields table once
        //  the Reducto field schema is finalised. For now only the status is updated.
        return dsl.update(EXTRACTIONS)
                .set(EXTRACTIONS.STATUS, ExtractionStatus.COMPLETED.getValue())
                .set(EXTRACTIONS.VERSION, EXTRACTIONS.VERSION.plus(1))
                .set(EXTRACTIONS.UPDATED_AT, OffsetDateTime.now())
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
                .set(EXTRACTIONS.UPDATED_AT, OffsetDateTime.now())
                .where(EXTRACTIONS.ID.eq(id))
                .and(EXTRACTIONS.STATUS.eq(ExtractionStatus.PROCESSING.getValue()))
                .and(EXTRACTIONS.DELETED_AT.isNull())
                .execute();
    }

    @Override
    public int putDocumentExternalId(String extractionId, String documentId, ExternalId externalId) {
        // Raw bridge until jOOQ classes jar is regenerated with DOCUMENT_EXTERNAL_IDS.
        // jsonb_set second argument must be a text[] literal — it cannot be bound via
        // a JDBC ? placeholder because the driver sends text, not text[].
        // The path is built from documentId which is an internal UUID, not user input.
        String externalIdJson = serializeExternalId(externalId);
        String path = "'{%s}'".formatted(documentId);
        return dsl.execute(
                "UPDATE extractions " +
                "SET document_external_ids = jsonb_set(document_external_ids, " + path + ", ?::jsonb, true), " +
                "    updated_at = NOW() " +
                "WHERE id = ? AND deleted_at IS NULL",
                externalIdJson,
                extractionId);
    }

    @Override
    public Optional<ExtractionWithDocs> findByDocumentExternalTaskId(String externalTaskId) {
        // Scan the document_external_ids JSONB map for any value whose
        // externalTaskId field matches. The subquery unnests the map values via
        // jsonb_each and filters by the target task ID.
        // Raw bridge until jOOQ classes jar is regenerated with DOCUMENT_EXTERNAL_IDS.
        return dsl.selectFrom(EXTRACTIONS)
                .where(EXTRACTIONS.DELETED_AT.isNull())
                .and(DSL.condition(
                        "EXISTS (" +
                        "  SELECT 1 FROM jsonb_each(document_external_ids) AS e(k, v) " +
                        "  WHERE v->>'externalTaskId' = {0}" +
                        ")",
                        externalTaskId))
                .fetchOptional()
                .map(record -> new ExtractionWithDocs(record, parseDocumentIds(record)));
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

    private String serializeExternalId(ExternalId externalId) {
        try {
            return objectMapper.writeValueAsString(externalId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ExternalId", e);
        }
    }
}
