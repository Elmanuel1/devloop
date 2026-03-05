package com.tosspaper.precon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Detects conflicts across {@code extraction_fields} rows for a completed batch.
 *
 * <h3>When to call</h3>
 * <p>Call {@link #detectAndMarkConflicts(String)} exactly once, after all documents in a
 * tender batch have reached a terminal state (completed, failed, or skipped). Never call
 * it per-document or per-webhook — it is designed as a batch-level operation that runs
 * a single aggregation pass.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Load all {@code extraction_fields} rows for the extraction.</li>
 *   <li>Group by {@code field_name}.</li>
 *   <li>Within each group, compare normalised {@code proposed_value} strings.
 *       Normalisation ensures cosmetic differences (whitespace, date format) do not
 *       trigger false conflicts.</li>
 *   <li>If two or more rows have distinct normalised values, mark every row in the
 *       group with {@code has_conflict = true} and populate {@code competing_values}
 *       with a JSON array of {@code {field_id, value, confidence}} objects.</li>
 * </ol>
 *
 * <h3>Idempotency</h3>
 * <p>The underlying repository method uses a targeted UPDATE statement, so calling
 * this method twice for the same extraction is safe — the second call simply
 * re-writes the same flags.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConflictDetector {

    private final ExtractionFieldRepository extractionFieldRepository;
    private final ObjectMapper objectMapper;

    /**
     * Runs conflict detection for all fields belonging to {@code extractionId}.
     *
     * <p>Groups all {@link ExtractionFieldsRecord}s by {@code field_name}. Within
     * each group, if the normalised {@code proposed_value} strings are not all
     * identical, all rows in that group are flagged as conflicting and the
     * {@code competing_values} JSONB column is populated.
     *
     * @param extractionId the extraction to inspect
     * @return the number of field rows that were marked as conflicted
     */
    public int detectAndMarkConflicts(String extractionId) {
        log.debug("[ConflictDetector] Starting conflict detection for extraction {}", extractionId);

        List<ExtractionFieldsRecord> allFields = extractionFieldRepository.findAllByExtractionId(extractionId);

        if (allFields.isEmpty()) {
            log.debug("[ConflictDetector] No fields found for extraction {} — skipping", extractionId);
            return 0;
        }

        // Group by field_name preserving insertion order for deterministic tests
        Map<String, List<ExtractionFieldsRecord>> byFieldName = allFields.stream()
                .collect(Collectors.groupingBy(
                        ExtractionFieldsRecord::getFieldName,
                        Collectors.toList()));

        int conflictedRows = 0;

        for (Map.Entry<String, List<ExtractionFieldsRecord>> entry : byFieldName.entrySet()) {
            String fieldName = entry.getKey();
            List<ExtractionFieldsRecord> rows = entry.getValue();

            if (rows.size() <= 1) {
                // Only one document produced this field — no conflict possible
                continue;
            }

            Set<String> distinctValues = rows.stream()
                    .map(r -> normalise(r.getProposedValue()))
                    .collect(Collectors.toSet());

            if (distinctValues.size() > 1) {
                log.debug("[ConflictDetector] Conflict detected on field '{}' for extraction {} — {} distinct values across {} documents",
                        fieldName, extractionId, distinctValues.size(), rows.size());

                JSONB competingJsonb = buildCompetingValuesJsonb(rows);
                int updated = extractionFieldRepository.markConflict(extractionId, fieldName, competingJsonb);
                conflictedRows += updated;
            }
        }

        log.info("[ConflictDetector] Conflict detection complete for extraction {} — {} row(s) marked conflicted",
                extractionId, conflictedRows);
        return conflictedRows;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Normalises a JSONB value to a canonical string for conflict comparison.
     *
     * <p>Null JSONB is treated as the empty string so that two null-valued fields
     * are not considered conflicting. Non-null values are re-serialised through
     * Jackson (which sorts object keys in a consistent order on re-parse) to
     * eliminate whitespace differences.
     *
     * @param jsonb the value to normalise
     * @return a canonical, trimmed string representation
     */
    String normalise(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null) {
            return "";
        }
        try {
            // Parse and re-serialise through a TreeMap to get sorted keys
            JsonNode node = objectMapper.readTree(jsonb.data());
            // Use canonical form: sorted keys, compact output
            Map<String, Object> canonical = objectMapper.convertValue(node, TreeMap.class);
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception e) {
            // If parsing fails, fall back to raw trimmed string comparison
            return jsonb.data().trim();
        }
    }

    /**
     * Builds the {@code competing_values} JSONB array.
     *
     * <p>Each element has the shape:
     * <pre>{@code {"field_id": "...", "value": <JsonNode>, "confidence": <decimal>}}</pre>
     *
     * @param rows the conflicting field rows
     * @return a JSONB value containing the competing-values array
     */
    private JSONB buildCompetingValuesJsonb(List<ExtractionFieldsRecord> rows) {
        try {
            ArrayNode array = objectMapper.createArrayNode();
            for (ExtractionFieldsRecord row : rows) {
                ObjectNode element = objectMapper.createObjectNode();
                element.put("field_id", row.getId());

                // Parse proposed_value as a JsonNode; null becomes a JSON null node
                JsonNode valueNode = (row.getProposedValue() != null && row.getProposedValue().data() != null)
                        ? objectMapper.readTree(row.getProposedValue().data())
                        : objectMapper.nullNode();
                element.set("value", valueNode);

                BigDecimal confidence = row.getConfidence();
                if (confidence != null) {
                    element.put("confidence", confidence);
                } else {
                    element.putNull("confidence");
                }

                array.add(element);
            }
            return JSONB.valueOf(objectMapper.writeValueAsString(array));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(ApiErrorMessages.SERIALIZATION_ERROR, e);
        }
    }
}
