package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.agents.judge.DeterministicJudge;
import org.springaicommunity.agents.judge.context.JudgmentContext;
import org.springaicommunity.agents.judge.result.Judgment;

/**
 * Deterministic judge that validates the results file is a valid wrapper object.
 *
 * <p>Validation checks:
 * <ul>
 *   <li>File exists and can be read</li>
 *   <li>Content is valid JSON</li>
 *   <li>Root element is an object with documentId, poId, and results array</li>
 * </ul>
 */
public class JsonObjectJudge extends DeterministicJudge {

    private static final String NAME = "json-object";
    private static final String DESCRIPTION = "Validates that the results file contains a valid wrapper object";

    private final ComparisonResultsReader reader;

    public JsonObjectJudge(ComparisonResultsReader reader) {
        super(NAME, DESCRIPTION);
        this.reader = reader;
    }

    @Override
    public Judgment judge(JudgmentContext context) {
        JsonNode root = reader.read();

        if (!reader.fileExists()) {
            return Judgment.fail("File not found: " + reader.getFilePath());
        }

        if (!reader.isValidJson()) {
            return Judgment.fail(reader.getError());
        }

        if (!reader.isObject()) {
            return Judgment.fail("Expected JSON object, got: " + root.getNodeType());
        }

        // Validate required wrapper fields
        if (!root.has("documentId") || root.get("documentId").isNull()) {
            return Judgment.fail("Missing required field: documentId");
        }

        if (!root.has("poId") || root.get("poId").isNull()) {
            return Judgment.fail("Missing required field: poId");
        }

        if (!reader.hasResults()) {
            return Judgment.fail("Missing or invalid 'results' array");
        }

        JsonNode results = reader.getResults();
        return Judgment.pass("Valid wrapper object with " + results.size() + " results");
    }
}
