package com.tosspaper.precon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
import java.util.stream.Collectors;

/** Detects and marks conflicting extraction field values across documents in a batch. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConflictDetector {

    private final ExtractionFieldRepository extractionFieldRepository;
    private final ObjectMapper objectMapper;

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
                // TODO (follow-up PR): pass competing_values through a reasoning model to filter out
                // superficial differences (e.g. "$45.00" vs "$45", "Excavation" vs "excavation")
                // before surfacing as genuine conflicts to the reviewer.
            }
        }

        log.info("[ConflictDetector] Conflict detection complete for extraction {} — {} row(s) marked conflicted",
                extractionId, conflictedRows);
        return conflictedRows;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    String normalise(JSONB jsonb) {
        if (jsonb == null || jsonb.data() == null) {
            return "";
        }
        try {
            // Deserialise to Object so nested maps are LinkedHashMap, then re-serialise
            // with ORDER_MAP_ENTRIES_BY_KEYS which recursively sorts keys at every depth.
            Object value = objectMapper.readValue(jsonb.data(), Object.class);
            return objectMapper.copy()
                    .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(value);
        } catch (Exception e) {
            // If parsing fails, fall back to raw trimmed string comparison
            return jsonb.data().trim();
        }
    }

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
