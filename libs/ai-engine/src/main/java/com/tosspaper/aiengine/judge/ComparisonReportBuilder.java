package com.tosspaper.aiengine.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.judge.jury.Verdict;
import org.springaicommunity.judge.result.Judgment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ComparisonVerificationReport} from comparison results and verdict.
 *
 * <p>Converts the raw JSON results from the agent into a structured report
 * suitable for UI consumption, including:
 * <ul>
 *   <li>Summary counts (matched, unmatched, discrepancies)</li>
 *   <li>Contact comparisons with discrepancy details</li>
 *   <li>Line item comparisons with index linking (documentIndex ↔ poIndex)</li>
 *   <li>Verification check results from judges</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ComparisonReportBuilder {

    private final ObjectMapper objectMapper;

    /**
     * Builds a report from the results file and verdict.
     *
     * @param resultsPath Path to the comparison results JSON file
     * @param verdict     The verdict from running the judge chain
     * @return ComparisonVerificationReport for UI consumption
     */
    public ComparisonVerificationReport buildReport(Path resultsPath, Verdict verdict) {
        ComparisonResultsReader reader = new ComparisonResultsReader(resultsPath, objectMapper);
        JsonNode results = reader.getResults();

        boolean structureValid = verdict.aggregated().pass();

        // Build verification checks from verdict
        List<ComparisonVerificationReport.VerificationCheck> checks = new ArrayList<>();
        for (Map.Entry<String, Judgment> entry : verdict.individualByName().entrySet()) {
            checks.add(ComparisonVerificationReport.VerificationCheck.builder()
                    .name(entry.getKey())
                    .passed(entry.getValue().pass())
                    .reasoning(entry.getValue().reasoning())
                    .build());
        }

        // If structure is invalid, return error report
        if (!structureValid || results == null || !reader.hasResults()) {
            return ComparisonVerificationReport.builder()
                    .structureValid(false)
                    .status("ERROR")
                    .timestamp(Instant.now())
                    .checks(checks)
                    .build();
        }

        // Parse results
        ComparisonVerificationReport.ContactComparison vendorContact = null;
        ComparisonVerificationReport.ContactComparison shipToContact = null;
        List<ComparisonVerificationReport.LineItemComparison> lineItems = new ArrayList<>();

        int matchedItems = 0;
        int unmatchedItems = 0;
        int itemsWithDiscrepancies = 0;

        for (JsonNode entry : results) {
            String type = entry.has("type") ? entry.get("type").asText() : null;

            if ("vendor".equals(type)) {
                vendorContact = parseContactComparison(entry);
            } else if ("ship_to".equals(type)) {
                shipToContact = parseContactComparison(entry);
            } else if ("line_item".equals(type)) {
                ComparisonVerificationReport.LineItemComparison lineItem = parseLineItemComparison(entry);
                lineItems.add(lineItem);

                if (lineItem.isMatched()) {
                    matchedItems++;
                } else {
                    unmatchedItems++;
                }

                if (lineItem.getDiscrepancies() != null && !lineItem.getDiscrepancies().isEmpty()) {
                    itemsWithDiscrepancies++;
                }
            }
        }

        return ComparisonVerificationReport.builder()
                .structureValid(true)
                .status("COMPLETE")
                .timestamp(Instant.now())
                .totalDocumentItems(lineItems.size())
                .matchedItems(matchedItems)
                .unmatchedItems(unmatchedItems)
                .itemsWithDiscrepancies(itemsWithDiscrepancies)
                .vendorContact(vendorContact)
                .shipToContact(shipToContact)
                .lineItems(lineItems)
                .checks(checks)
                .build();
    }

    private ComparisonVerificationReport.ContactComparison parseContactComparison(JsonNode entry) {
        return ComparisonVerificationReport.ContactComparison.builder()
                .matched(hasMatch(entry))
                .matchScore(getDouble(entry, "matchScore"))
                .matchReasons(getReasons(entry))
                .discrepancies(parseDiscrepancies(entry.get("discrepancies")))
                .build();
    }

    private ComparisonVerificationReport.LineItemComparison parseLineItemComparison(JsonNode entry) {
        Integer poIndex = null;
        if (entry.has("poIndex") && !entry.get("poIndex").isNull()) {
            poIndex = entry.get("poIndex").asInt();
        }

        return ComparisonVerificationReport.LineItemComparison.builder()
                .documentIndex(entry.get("extractedIndex").asInt())
                .poIndex(poIndex)
                .matched(poIndex != null)
                .matchScore(getDouble(entry, "matchScore"))
                .matchReasons(getReasons(entry))
                .discrepancies(parseDiscrepancies(entry.get("discrepancies")))
                .build();
    }

    private Map<String, ComparisonVerificationReport.DiscrepancyDetail> parseDiscrepancies(JsonNode discrepanciesNode) {
        if (discrepanciesNode == null || discrepanciesNode.isNull() || !discrepanciesNode.isObject()) {
            return new HashMap<>();
        }

        Map<String, ComparisonVerificationReport.DiscrepancyDetail> result = new HashMap<>();
        discrepanciesNode.fields().forEachRemaining(field -> {
            JsonNode detail = field.getValue();
            result.put(field.getKey(), ComparisonVerificationReport.DiscrepancyDetail.builder()
                    .documentValue(getNodeValue(detail, "extracted"))
                    .poValue(getNodeValue(detail, "po"))
                    .difference(getNodeValue(detail, "difference"))
                    .build());
        });
        return result;
    }

    private boolean hasMatch(JsonNode entry) {
        // Contact is considered matched if matchScore is present and > 0
        if (entry.has("matchScore") && !entry.get("matchScore").isNull()) {
            return entry.get("matchScore").asDouble() > 0;
        }
        // Or if reasons doesn't indicate "no match"
        String reasons = getReasons(entry);
        return reasons != null && !reasons.toLowerCase().contains("no match");
    }

    private String getReasons(JsonNode entry) {
        if (!entry.has("reasons") || entry.get("reasons").isNull()) {
            return null;
        }
        JsonNode reasonsNode = entry.get("reasons");
        if (reasonsNode.isArray()) {
            List<String> reasonsList = new ArrayList<>();
            reasonsNode.forEach(r -> reasonsList.add(r.asText()));
            return String.join("; ", reasonsList);
        }
        return reasonsNode.asText();
    }

    private Double getDouble(JsonNode entry, String field) {
        if (entry.has(field) && !entry.get(field).isNull()) {
            return entry.get(field).asDouble();
        }
        return null;
    }

    private String getString(JsonNode entry, String field) {
        if (entry.has(field) && !entry.get(field).isNull()) {
            return entry.get(field).asText();
        }
        return null;
    }

    private Object getNodeValue(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            if (value.isInt()) return value.asInt();
            if (value.isLong()) return value.asLong();
            return value.asDouble();
        }
        if (value.isBoolean()) return value.asBoolean();
        return value.asText();
    }
}
