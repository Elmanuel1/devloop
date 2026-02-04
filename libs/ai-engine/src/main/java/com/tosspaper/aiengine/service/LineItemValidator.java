package com.tosspaper.aiengine.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.aiengine.tools.FileTools;
import com.tosspaper.aiengine.tools.FileTools.PoItemInfo;
import com.tosspaper.aiengine.tools.FileTools.ValidationResult;
import com.tosspaper.models.extraction.dto.Comparison;
import com.tosspaper.models.extraction.dto.ComparisonResult;
import com.tosspaper.models.extraction.dto.FieldComparison;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for post-hoc validation and correction of line item matches.
 *
 * <p>This service separates validation concerns from the main AI comparison:
 * <ol>
 *   <li>Main AI generates comparison JSON freely (no inline validation)</li>
 *   <li>This service validates each line_item's poIndex afterwards</li>
 *   <li>For invalid matches, uses a BATCH correction AI call to find correct indices</li>
 *   <li>Updates the comparison with corrected values</li>
 * </ol>
 */
@Slf4j
@Component
public class LineItemValidator {

    private static final int MAX_CORRECTION_RETRIES = 2;

    private final FileTools fileTools;
    private final ChatClient comparisonChatClient;
    private final ObjectMapper objectMapper;

    public LineItemValidator(
            FileTools fileTools,
            @Qualifier("comparisonChatClient") ChatClient comparisonChatClient,
            ObjectMapper objectMapper) {
        this.fileTools = fileTools;
        this.comparisonChatClient = comparisonChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Record representing a failed validation with original result for correction.
     */
    public record FailedValidation(
            ValidationResult validation,
            ComparisonResult originalResult
    ) {}

    /**
     * Batch result containing validated and failed line items.
     */
    public record ValidationBatch(
            List<ValidationResult> validated,
            List<FailedValidation> failed,
            Set<Integer> usedPoIndices
    ) {}

    /**
     * Result of batch correction attempt.
     */
    public record BatchCorrectionResult(
            Map<Integer, Integer> corrections,  // docIndex -> correctedPoIndex
            Set<Integer> uncorrectable          // docIndex values that couldn't be corrected
    ) {}

    /**
     * Validate all line_items from comparison result.
     * Returns two lists: validated and failed (with original results for correction).
     */
    public ValidationBatch validateLineItems(Comparison comparison) {
        List<ValidationResult> validated = new ArrayList<>();
        List<FailedValidation> failed = new ArrayList<>();
        Set<Integer> usedPoIndices = new HashSet<>();

        log.info("=== POST-HOC VALIDATION START === Total results: {}",
                comparison.getResults() != null ? comparison.getResults().size() : 0);

        if (comparison.getResults() == null) {
            return new ValidationBatch(validated, failed, usedPoIndices);
        }

        int lineItemCount = 0;
        for (ComparisonResult result : comparison.getResults()) {
            if (result.getType() == null || !"line_item".equals(result.getType().value())) {
                continue;
            }
            lineItemCount++;

            if (result.getPoIndex() == null) {
                log.info("[SKIP] docIndex={} has no poIndex (already marked unmatched)",
                        result.getExtractedIndex());
                continue;
            }

            int docIndex = result.getExtractedIndex() != null ? result.getExtractedIndex().intValue() : -1;
            int poIndex = result.getPoIndex().intValue();
            String itemCode = extractItemCode(result);
            String description = extractDescription(result);

            log.info("[VALIDATING] docIndex={} → poIndex={} | code='{}' desc='{}'",
                    docIndex, poIndex, itemCode, truncate(description, 40));

            ValidationResult vr = fileTools.validateLineItemMatch(poIndex, itemCode, description);

            if (vr.valid() && !usedPoIndices.contains(poIndex)) {
                log.info("[VALID ✓] docIndex={} → poIndex={}", docIndex, poIndex);
                ValidationResult completeVr = new ValidationResult(
                        true, docIndex, poIndex, itemCode, description,
                        vr.actualItemCode(), vr.actualDescription());
                validated.add(completeVr);
                usedPoIndices.add(poIndex);
            } else {
                String reason = !vr.valid() ? "wrong index" :
                        "poIndex " + poIndex + " already used";
                log.warn("[INVALID ✗] docIndex={} → poIndex={} | reason: {} | actual: code='{}' desc='{}'",
                        docIndex, poIndex, reason, vr.actualItemCode(), truncate(vr.actualDescription(), 40));

                ValidationResult failedVr = new ValidationResult(
                        false, docIndex, poIndex, itemCode, description,
                        vr.actualItemCode(), vr.actualDescription());
                failed.add(new FailedValidation(failedVr, result));
            }
        }

        log.info("=== POST-HOC VALIDATION COMPLETE === lineItems={} valid={} failed={}",
                lineItemCount, validated.size(), failed.size());

        return new ValidationBatch(validated, failed, usedPoIndices);
    }

    /**
     * Correct all failed items in a SINGLE batch AI call.
     * Much more token-efficient than one-by-one corrections.
     *
     * @param failedItems List of failed validations to correct
     * @param usedPoIndices Set of already-used PO indices (do not use these)
     * @param availablePoItems List of available PO items with their indices
     * @return BatchCorrectionResult with corrections and uncorrectable items
     */
    public BatchCorrectionResult correctFailedItemsBatch(
            List<FailedValidation> failedItems,
            Set<Integer> usedPoIndices,
            List<PoItemInfo> availablePoItems) {

        if (failedItems.isEmpty()) {
            return new BatchCorrectionResult(Map.of(), Set.of());
        }

        log.info("=== BATCH CORRECTION START === {} items to correct, {} PO items available",
                failedItems.size(), availablePoItems.size());

        Map<Integer, Integer> corrections = new HashMap<>();
        Set<Integer> uncorrectable = new HashSet<>();
        Set<Integer> currentUsedIndices = new HashSet<>(usedPoIndices);

        // Items still needing correction
        List<FailedValidation> remaining = new ArrayList<>(failedItems);

        for (int attempt = 1; attempt <= MAX_CORRECTION_RETRIES && !remaining.isEmpty(); attempt++) {
            log.info("[BATCH ATTEMPT {}/{}] {} items remaining", attempt, MAX_CORRECTION_RETRIES, remaining.size());

            try {
                String prompt = buildBatchCorrectionPrompt(remaining, currentUsedIndices, availablePoItems);

                String response = comparisonChatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();

                Map<Integer, Integer> batchResult = parseBatchCorrections(response);
                log.info("[BATCH RESPONSE] Got {} corrections", batchResult.size());

                // Validate each correction
                List<FailedValidation> stillFailed = new ArrayList<>();

                for (FailedValidation item : remaining) {
                    int docIndex = item.validation().docIndex();
                    Integer correctedPoIndex = batchResult.get(docIndex);

                    if (correctedPoIndex == null) {
                        // AI said no match - mark as uncorrectable
                        log.info("[NO MATCH] docIndex={} marked as unmatched by AI", docIndex);
                        uncorrectable.add(docIndex);
                        continue;
                    }

                    // Validate the correction
                    if (currentUsedIndices.contains(correctedPoIndex)) {
                        log.warn("[COLLISION] docIndex={} → poIndex={} already used, will retry",
                                docIndex, correctedPoIndex);
                        stillFailed.add(item);
                        continue;
                    }

                    ValidationResult validation = fileTools.validateLineItemMatch(
                            correctedPoIndex, item.validation().itemCode(), item.validation().description());

                    if (validation.valid()) {
                        log.info("[CORRECTED ✓] docIndex={} → poIndex={}", docIndex, correctedPoIndex);
                        corrections.put(docIndex, correctedPoIndex);
                        currentUsedIndices.add(correctedPoIndex);
                    } else {
                        log.warn("[WRONG INDEX] docIndex={} → poIndex={} invalid (actual: {}), will retry",
                                docIndex, correctedPoIndex, validation.actualItemCode());
                        stillFailed.add(item);
                    }
                }

                remaining = stillFailed;

            } catch (Exception e) {
                log.error("[BATCH ERROR] attempt={} error: {}", attempt, e.getMessage());
            }
        }

        // Any remaining items are uncorrectable
        for (FailedValidation item : remaining) {
            uncorrectable.add(item.validation().docIndex());
        }

        log.info("=== BATCH CORRECTION COMPLETE === corrected={} uncorrectable={}",
                corrections.size(), uncorrectable.size());

        return new BatchCorrectionResult(corrections, uncorrectable);
    }

    /**
     * Get available PO items (excluding used indices) for the correction prompt.
     */
    public List<PoItemInfo> getAvailablePoItems(Set<Integer> usedIndices) {
        return fileTools.getPoItemsList().stream()
                .filter(item -> !usedIndices.contains(item.index()))
                .collect(Collectors.toList());
    }

    /**
     * Build batch correction prompt with all failed items and available PO items.
     */
    private String buildBatchCorrectionPrompt(
            List<FailedValidation> failedItems,
            Set<Integer> usedPoIndices,
            List<PoItemInfo> availablePoItems) {

        StringBuilder failedList = new StringBuilder();
        for (FailedValidation item : failedItems) {
            ValidationResult v = item.validation();
            failedList.append(String.format(
                    "- docIndex=%d: itemCode='%s', description='%s' (wrongly matched to poIndex=%d)\n",
                    v.docIndex(), v.itemCode(), truncate(v.description(), 60), v.poIndex()));
        }

        StringBuilder availableList = new StringBuilder();
        for (PoItemInfo po : availablePoItems) {
            availableList.append(String.format(
                    "- poIndex=%d: itemCode='%s', description='%s'\n",
                    po.index(), po.itemCode(), truncate(po.description(), 60)));
        }

        return """
            Fix these INCORRECTLY matched line items. Return the correct poIndex for each.

            ## DOCUMENT LINE ITEMS TO FIX
            %s

            ## AVAILABLE PO ITEMS (not yet matched)
            %s

            ## RESERVED PO INDICES (DO NOT USE)
            %s

            ## MATCHING RULES
            1. Item codes MUST match exactly (case-insensitive)
            2. Descriptions must be the same product type
            3. Each PO item can only be used ONCE
            4. If no valid match exists, return null for that docIndex

            ## RESPONSE FORMAT
            Return a JSON object mapping docIndex to correctedPoIndex:
            ```json
            {
              "3": 15,
              "7": 22,
              "12": null
            }
            ```

            ONLY return the JSON object, no explanation.
            """.formatted(failedList, availableList, usedPoIndices);
    }

    /**
     * Parse batch corrections from AI response.
     * Returns map of docIndex -> correctedPoIndex (null values mean no match).
     */
    private Map<Integer, Integer> parseBatchCorrections(String response) {
        Map<Integer, Integer> corrections = new HashMap<>();

        try {
            String json = extractJson(response);
            if (json == null) {
                log.warn("[PARSE] Could not extract JSON from response");
                return corrections;
            }

            // Parse as Map<String, Object> to handle null values
            Map<String, Object> rawMap = objectMapper.readValue(json, new TypeReference<>() {});

            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                try {
                    int docIndex = Integer.parseInt(entry.getKey());
                    Integer poIndex = entry.getValue() == null ? null :
                            ((Number) entry.getValue()).intValue();
                    corrections.put(docIndex, poIndex);
                } catch (Exception e) {
                    log.warn("[PARSE] Failed to parse entry: {}={}", entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            log.error("[PARSE] Failed to parse batch corrections: {}", e.getMessage());
        }

        return corrections;
    }

    /**
     * Extract item code from comparison result.
     */
    private String extractItemCode(ComparisonResult result) {
        if (result.getComparisons() == null) return "";

        for (FieldComparison fc : result.getComparisons()) {
            String field = fc.getField();
            if (field != null && (field.equalsIgnoreCase("Item Code") ||
                    field.equalsIgnoreCase("ItemCode") ||
                    field.equalsIgnoreCase("SKU") ||
                    field.equalsIgnoreCase("Unit Code"))) {
                return fc.getDocumentValue() != null ? fc.getDocumentValue() : "";
            }
        }
        return "";
    }

    /**
     * Extract description from comparison result.
     */
    private String extractDescription(ComparisonResult result) {
        if (result.getComparisons() == null) return "";

        for (FieldComparison fc : result.getComparisons()) {
            String field = fc.getField();
            if (field != null && (field.equalsIgnoreCase("Description") ||
                    field.equalsIgnoreCase("Name") ||
                    field.equalsIgnoreCase("Product"))) {
                return fc.getDocumentValue() != null ? fc.getDocumentValue() : "";
            }
        }
        return "";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    private String extractJson(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }

        String trimmed = rawResponse.trim();

        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            if (start == -1) return null;

            int end = trimmed.lastIndexOf("```");
            if (end <= start) return null;

            return trimmed.substring(start + 1, end).trim();
        }

        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }

        return null;
    }
}
