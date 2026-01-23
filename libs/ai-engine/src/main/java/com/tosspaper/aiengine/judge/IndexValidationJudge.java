package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.judge.DeterministicJudge;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.result.Judgment;

import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic judge that validates extractedIndex values for UI linking.
 *
 * <p>Validation checks:
 * <ul>
 *   <li>All line_item entries have extractedIndex</li>
 *   <li>extractedIndex values are 0 to (N-1) with no gaps</li>
 *   <li>No duplicate extractedIndex values</li>
 *   <li>poIndex is null or a valid integer (no further validation)</li>
 * </ul>
 */
public class IndexValidationJudge extends DeterministicJudge {

    private static final String NAME = "index-validation";
    private static final String DESCRIPTION = "Validates that extractedIndex values are valid for UI linking";

    private final ComparisonResultsReader reader;
    private final int expectedDocumentItemCount;

    public IndexValidationJudge(ComparisonResultsReader reader, int expectedDocumentItemCount) {
        super(NAME, DESCRIPTION);
        this.reader = reader;
        this.expectedDocumentItemCount = expectedDocumentItemCount;
    }

    @Override
    public Judgment judge(JudgmentContext context) {
        JsonNode results = reader.getResults();

        // Skip if previous judge already failed
        if (results == null || !reader.hasResults()) {
            return Judgment.abstain("Skipped: results not available (previous judge failed)");
        }

        Set<Integer> extractedIndices = new HashSet<>();
        int lineItemCount = 0;

        for (JsonNode entry : results) {
            String type = entry.has("type") ? entry.get("type").asText() : null;

            if (!"line_item".equals(type)) {
                continue;
            }

            lineItemCount++;

            if (!entry.has("extractedIndex")) {
                return Judgment.fail("Line item missing extractedIndex");
            }

            JsonNode extractedIndexNode = entry.get("extractedIndex");
            if (!extractedIndexNode.isInt()) {
                return Judgment.fail("extractedIndex must be an integer, got: " + extractedIndexNode.getNodeType());
            }

            int extractedIndex = extractedIndexNode.asInt();

            if (extractedIndex < 0) {
                return Judgment.fail("extractedIndex cannot be negative: " + extractedIndex);
            }

            if (!extractedIndices.add(extractedIndex)) {
                return Judgment.fail("Duplicate extractedIndex: " + extractedIndex);
            }

            // Validate poIndex if present (must be null or integer)
            if (entry.has("poIndex") && !entry.get("poIndex").isNull()) {
                JsonNode poIndexNode = entry.get("poIndex");
                if (!poIndexNode.isInt()) {
                    return Judgment.fail("poIndex must be null or integer, got: " + poIndexNode.getNodeType());
                }
            }
        }

        // Check we have the expected number of line items
        if (lineItemCount != expectedDocumentItemCount) {
            return Judgment.fail("Expected " + expectedDocumentItemCount + " line items, found " + lineItemCount);
        }

        // Check for gaps in sequence (0 to N-1)
        for (int i = 0; i < expectedDocumentItemCount; i++) {
            if (!extractedIndices.contains(i)) {
                return Judgment.fail("Missing extractedIndex " + i + " in sequence 0.." + (expectedDocumentItemCount - 1));
            }
        }

        return Judgment.pass("All " + lineItemCount + " line items have valid indices (0.." + (lineItemCount - 1) + ")");
    }
}
