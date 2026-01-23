package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import org.springaicommunity.judge.DeterministicJudge;
import org.springaicommunity.judge.context.JudgmentContext;
import org.springaicommunity.judge.result.Judgment;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic judge that validates contact coverage in comparison results.
 *
 * <p>Validation checks:
 * <ul>
 *   <li>vendor entry exists</li>
 *   <li>ship_to entry exists</li>
 *   <li>Both contacts have reasons array (can be ["No match found"])</li>
 * </ul>
 */
public class ContactCoverageJudge extends DeterministicJudge {

    private static final String NAME = "contact-coverage";
    private static final String DESCRIPTION = "Validates that both vendor and ship-to contacts are present in results";

    private final ComparisonResultsReader reader;

    public ContactCoverageJudge(ComparisonResultsReader reader) {
        super(NAME, DESCRIPTION);
        this.reader = reader;
    }

    @Override
    public Judgment judge(JudgmentContext context) {
        JsonNode results = reader.getResults();

        // Skip if previous judge already failed
        if (results == null || !reader.hasResults()) {
            return Judgment.abstain("Skipped: results not available (previous judge failed)");
        }

        boolean hasVendor = false;
        boolean hasShipTo = false;
        List<String> errors = new ArrayList<>();

        for (JsonNode entry : results) {
            String type = entry.has("type") ? entry.get("type").asText() : null;

            if ("vendor".equals(type)) {
                hasVendor = true;
                if (!entry.has("reasons") || entry.get("reasons").isNull()) {
                    errors.add("vendor missing reasons");
                }
            } else if ("ship_to".equals(type)) {
                hasShipTo = true;
                if (!entry.has("reasons") || entry.get("reasons").isNull()) {
                    errors.add("ship_to missing reasons");
                }
            }
        }

        if (!hasVendor) {
            errors.add("Missing vendor entry");
        }
        if (!hasShipTo) {
            errors.add("Missing ship_to entry");
        }

        if (!errors.isEmpty()) {
            return Judgment.fail("Contact coverage incomplete: " + String.join("; ", errors));
        }

        return Judgment.pass("Both vendor and ship_to present with reasons");
    }
}
