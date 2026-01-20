package com.tosspaper.aiengine.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.jooq.tables.records.DocumentPartComparisonsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.DOCUMENT_PART_COMPARISONS;
import static com.tosspaper.models.jooq.Tables.EXTRACTION_TASK;

/**
 * JOOQ implementation of DocumentPartComparisonRepository.
 * Stores comparison results as JSONB for flexible querying.
 */
@Slf4j
@Repository("aiEngineDocumentPartComparisonRepository")
@RequiredArgsConstructor
public class DocumentPartComparisonRepositoryImpl implements DocumentPartComparisonRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    public void upsert(DSLContext ctx, String extractionId, Comparison result) {
        try {
            String jsonData = objectMapper.writeValueAsString(result);

            ctx.insertInto(DOCUMENT_PART_COMPARISONS)
                .set(DOCUMENT_PART_COMPARISONS.EXTRACTION_ID, extractionId)
                .set(DOCUMENT_PART_COMPARISONS.DOCUMENT_ID, result.getDocumentId())
                .set(DOCUMENT_PART_COMPARISONS.PO_ID, result.getPoId())
                .set(DOCUMENT_PART_COMPARISONS.OVERALL_STATUS,
                     result.getOverallStatus() != null ? result.getOverallStatus().value() : null)
                .set(DOCUMENT_PART_COMPARISONS.RESULT_DATA, JSONB.jsonb(jsonData))
                .onConflict(DOCUMENT_PART_COMPARISONS.EXTRACTION_ID)
                .doUpdate()
                .set(DOCUMENT_PART_COMPARISONS.DOCUMENT_ID, result.getDocumentId())
                .set(DOCUMENT_PART_COMPARISONS.PO_ID, result.getPoId())
                .set(DOCUMENT_PART_COMPARISONS.OVERALL_STATUS,
                     result.getOverallStatus() != null ? result.getOverallStatus().value() : null)
                .set(DOCUMENT_PART_COMPARISONS.RESULT_DATA, JSONB.jsonb(jsonData))
                .execute();

            log.info("Upserted comparison result: extractionId={}, documentId={}, poId={}, status={}",
                     extractionId, result.getDocumentId(), result.getPoId(),
                     result.getOverallStatus() != null ? result.getOverallStatus().value() : "null");

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize comparison result", e);
        }
    }

    @Override
    public int deleteByExtractionId(DSLContext ctx, String extractionId) {
        log.debug("Deleting comparison for extractionId: {}", extractionId);

        int deletedCount = ctx.deleteFrom(DOCUMENT_PART_COMPARISONS)
            .where(DOCUMENT_PART_COMPARISONS.EXTRACTION_ID.eq(extractionId))
            .execute();

        log.debug("Deleted {} comparison result(s) for extractionId: {}", deletedCount, extractionId);
        return deletedCount;
    }

    @Override
    public Optional<Comparison> findByExtractionId(String extractionId) {
        return dsl.selectFrom(DOCUMENT_PART_COMPARISONS)
            .where(DOCUMENT_PART_COMPARISONS.EXTRACTION_ID.eq(extractionId))
            .fetchOptional()
            .map(this::toComparison);
    }

    @Override
    public Optional<Comparison> findByAssignedId(String assignedId, Long companyId) {
        log.debug("Finding comparison for assignedId: {}, companyId: {}", assignedId, companyId);

        return dsl.select(DOCUMENT_PART_COMPARISONS.asterisk())
            .from(DOCUMENT_PART_COMPARISONS)
            .join(EXTRACTION_TASK)
            .on(DOCUMENT_PART_COMPARISONS.EXTRACTION_ID.eq(EXTRACTION_TASK.ASSIGNED_ID))
            .where(EXTRACTION_TASK.ASSIGNED_ID.eq(assignedId))
            .and(EXTRACTION_TASK.COMPANY_ID.eq(companyId))
            .fetchOptional()
            .map(record -> toComparison(record.into(DOCUMENT_PART_COMPARISONS)));
    }

    private Comparison toComparison(DocumentPartComparisonsRecord record) {
        try {
            JSONB resultData = record.getResultData();
            if (resultData == null || resultData.data() == null) {
                log.warn("No result_data found for extraction: {}", record.getExtractionId());
                return null;
            }
            return objectMapper.readValue(resultData.data(), Comparison.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize comparison result for extraction: {}",
                      record.getExtractionId(), e);
            throw new RuntimeException("Failed to deserialize comparison result", e);
        }
    }
}
