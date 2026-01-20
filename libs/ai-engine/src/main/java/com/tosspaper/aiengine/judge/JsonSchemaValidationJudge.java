package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agents.judge.DeterministicJudge;
import org.springaicommunity.agents.judge.context.JudgmentContext;
import org.springaicommunity.agents.judge.result.Judgment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic judge that validates the results against a JSON schema.
 *
 * <p>Validates the complete wrapper object including:
 * <ul>
 *   <li>Required fields (documentId, poId, results)</li>
 *   <li>Field types and constraints</li>
 *   <li>Nested object structures</li>
 * </ul>
 */
@Slf4j
public class JsonSchemaValidationJudge extends DeterministicJudge {

    private static final String NAME = "json-schema-validation";
    private static final String DESCRIPTION = "Validates results against JSON schema";
    private static final int MAX_ERRORS_TO_SHOW = 5;

    private final ComparisonResultsReader reader;
    private final Path schemaPath;

    public JsonSchemaValidationJudge(ComparisonResultsReader reader, Path schemaPath) {
        super(NAME, DESCRIPTION);
        this.reader = reader;
        this.schemaPath = schemaPath;
    }

    @Override
    public Judgment judge(JudgmentContext context) {
        JsonNode root = reader.read();

        if (root == null || !reader.isValidJson()) {
            return Judgment.abstain("Skipped: results not available (previous judge failed)");
        }

        JsonSchema schema = loadSchema();
        if (schema == null) {
            return Judgment.fail("Failed to load schema from: " + schemaPath);
        }

        Set<ValidationMessage> errors = schema.validate(root);
        if (errors.isEmpty()) {
            return Judgment.pass("Results conform to schema");
        }

        String errorSummary = formatErrors(errors);
        return Judgment.fail("Schema validation failed: " + errorSummary);
    }

    private JsonSchema loadSchema() {
        try {
            String schemaContent = Files.readString(schemaPath);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(schemaContent);
        } catch (Exception e) {
            log.error("Failed to load schema from {}: {}", schemaPath, e.getMessage());
            return null;
        }
    }

    private String formatErrors(Set<ValidationMessage> errors) {
        String summary = errors.stream()
                .limit(MAX_ERRORS_TO_SHOW)
                .map(ValidationMessage::getMessage)
                .collect(Collectors.joining("; "));

        int remaining = errors.size() - MAX_ERRORS_TO_SHOW;
        if (remaining > 0) {
            summary += " (and " + remaining + " more errors)";
        }

        return summary;
    }
}
