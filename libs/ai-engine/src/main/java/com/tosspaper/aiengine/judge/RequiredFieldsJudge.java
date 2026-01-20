package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.agents.judge.DeterministicJudge;
import org.springaicommunity.agents.judge.context.JudgmentContext;
import org.springaicommunity.agents.judge.result.Judgment;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic judge that validates each result entry has required fields.
 *
 * <p>Required fields for all entries:
 * <ul>
 *   <li>type (vendor | ship_to | line_item)</li>
 *   <li>reasons (array)</li>
 *   <li>discrepancies</li>
 * </ul>
 *
 * <p>Additional required fields for line_item:
 * <ul>
 *   <li>extractedIndex</li>
 * </ul>
 */
public class RequiredFieldsJudge extends DeterministicJudge {

    private static final String NAME = "required-fields";
    private static final String DESCRIPTION = "Validates that each result entry has required fields";

    private static final List<String> REQUIRED_FIELDS = List.of("type", "reasons", "discrepancies");
    private static final List<String> LINE_ITEM_REQUIRED = List.of("extractedIndex");

    private final ComparisonResultsReader reader;

    public RequiredFieldsJudge(ComparisonResultsReader reader) {
        super(NAME, DESCRIPTION);
        this.reader = reader;
    }

    @Override
    public Judgment judge(JudgmentContext context) {
        JsonNode results = reader.getResults();

        // Skip if previous judge already failed (file not found, invalid JSON)
        if (results == null || !reader.hasResults()) {
            return Judgment.abstain("Skipped: results not available (previous judge failed)");
        }

        List<String> errors = new ArrayList<>();
        int index = 0;

        for (JsonNode entry : results) {
            // Check required fields for all entries
            for (String field : REQUIRED_FIELDS) {
                if (!entry.has(field) || entry.get(field).isNull()) {
                    errors.add("Entry " + index + " missing required field: " + field);
                }
            }

            // Check additional required fields for line_item
            String type = entry.has("type") ? entry.get("type").asText() : null;
            if ("line_item".equals(type)) {
                for (String field : LINE_ITEM_REQUIRED) {
                    if (!entry.has(field)) {
                        errors.add("Line item at index " + index + " missing required field: " + field);
                    }
                }
            }

            index++;
        }

        if (!errors.isEmpty()) {
            return Judgment.fail("Missing required fields: " + String.join("; ", errors));
        }

        return Judgment.pass("All " + index + " entries have required fields");
    }
}
