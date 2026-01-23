package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.judge.DeterministicJudge;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.result.Judgment;

import java.util.HashSet;
import java.util.Set;

/**
 * Deterministic judge that validates poIndex uniqueness (1:1 matching).
 *
 * <p>Validation checks:
 * <ul>
 *   <li>No two line items can have the same non-null poIndex</li>
 *   <li>Null poIndex values are allowed (unmatched items)</li>
 * </ul>
 */
public class PoIndexUniquenessJudge extends DeterministicJudge {

    private static final String NAME = "po-index-uniqueness";
    private static final String DESCRIPTION = "Validates that each PO item is matched at most once";

    private final ComparisonResultsReader reader;

    public PoIndexUniquenessJudge(ComparisonResultsReader reader) {
        super(NAME, DESCRIPTION);
        this.reader = reader;
    }

    @Override
    public Judgment judge(JudgmentContext context) {
        JsonNode results = reader.getResults();

        if (results == null || !reader.hasResults()) {
            return Judgment.abstain("Skipped: results not available (previous judge failed)");
        }

        Set<Integer> usedPoIndices = new HashSet<>();

        for (JsonNode entry : results) {
            String type = entry.has("type") ? entry.get("type").asText() : null;
            boolean isLineItem = "line_item".equals(type);
            if (!isLineItem) {
                continue;
            }

            boolean hasPoIndex = entry.has("poIndex") && !entry.get("poIndex").isNull();
            if (!hasPoIndex) {
                continue;
            }

            JsonNode poIndexNode = entry.get("poIndex");
            boolean isInteger = poIndexNode.isInt();
            if (!isInteger) {
                continue; // IndexValidationJudge handles type validation
            }

            int poIndex = poIndexNode.asInt();
            boolean isDuplicate = !usedPoIndices.add(poIndex);
            if (isDuplicate) {
                return Judgment.fail("Duplicate poIndex: " + poIndex + " - each PO item can only be matched once");
            }
        }

        return Judgment.pass("All poIndex values are unique (" + usedPoIndices.size() + " matched items)");
    }
}
