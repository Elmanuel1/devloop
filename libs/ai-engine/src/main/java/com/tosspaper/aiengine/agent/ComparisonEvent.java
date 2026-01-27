package com.tosspaper.aiengine.agent;

import com.tosspaper.models.extraction.dto.Comparison;

/**
 * Server-Sent Events for streaming comparison progress.
 * These events are sent to the frontend in real-time during AI comparison.
 *
 * <p>Event flow:
 * <ol>
 *   <li>Activity events show what the AI is doing (reading PO, analyzing invoice)</li>
 *   <li>Thinking events show Claude's extended thinking (reasoning process)</li>
 *   <li>Finding events show individual comparison results as they're discovered</li>
 *   <li>Complete event signals the end with final results and summary</li>
 *   <li>Error event signals a failure</li>
 * </ol>
 */
public sealed interface ComparisonEvent {

    /**
     * User-friendly activity message abstracted from internal tool calls.
     * Maps tool operations to readable descriptions.
     *
     * <p>Examples:
     * <ul>
     *   <li>"Reviewing purchase order PO-2024-001..."</li>
     *   <li>"Analyzing invoice INV-8847..."</li>
     *   <li>"Comparing vendor information..."</li>
     * </ul>
     *
     * @param icon    Emoji icon for the activity
     * @param message Human-readable description of the activity
     */
    record Activity(String icon, String message) implements ComparisonEvent {
        public static Activity reviewing(String documentType, String id) {
            return new Activity(getIcon(documentType), String.format("Reviewing %s %s...", documentType, id));
        }

        public static Activity analyzing(String documentType) {
            return new Activity("📄", String.format("Analyzing %s...", documentType));
        }

        public static Activity comparing(String aspect) {
            return new Activity("🔍", String.format("Comparing %s...", aspect));
        }

        public static Activity searching(String target) {
            return new Activity("🔍", String.format("Searching %s...", target));
        }

        public static Activity saving(String target) {
            return new Activity("💾", String.format("Saving %s...", target));
        }

        public static Activity processing() {
            return new Activity("⚙️", "Processing...");
        }

        private static String getIcon(String documentType) {
            return switch (documentType.toLowerCase()) {
                case "purchase order", "po" -> "📋";
                case "invoice" -> "📄";
                case "delivery slip", "delivery_slip" -> "📦";
                case "delivery note", "delivery_note" -> "📝";
                default -> "📄";
            };
        }
    }

    /**
     * AI thinking/reasoning content.
     * Streamed from Claude's extended thinking or simulated for OpenAI.
     *
     * @param content The thinking text
     */
    record Thinking(String content) implements ComparisonEvent {
        public static Thinking of(String content) {
            return new Thinking(content);
        }
    }

    /**
     * Individual comparison finding as it's discovered.
     * Shows match/mismatch status for a specific item.
     *
     * <p>Examples:
     * <ul>
     *   <li>✓ Vendor name: exact match</li>
     *   <li>⚠ Address: postal code differs</li>
     *   <li>✗ Widget B - price mismatch ($50 vs $55)</li>
     * </ul>
     *
     * @param icon   Status icon (✓, ⚠, ✗)
     * @param item   Item being compared (vendor, ship_to, line item description)
     * @param status Match status (matched, partial, unmatched)
     * @param detail Human-readable description of the finding
     */
    record Finding(String icon, String item, String status, String detail) implements ComparisonEvent {
        public static Finding match(String item, String detail) {
            return new Finding("✓", item, "matched", detail);
        }

        public static Finding partial(String item, String detail) {
            return new Finding("⚠", item, "partial", detail);
        }

        public static Finding mismatch(String item, String detail) {
            return new Finding("✗", item, "unmatched", detail);
        }
    }

    /**
     * Comparison complete with final results.
     *
     * @param result  The full Comparison object with all results
     * @param summary Summary statistics
     */
    record Complete(Comparison result, ComparisonSummary summary) implements ComparisonEvent {
        public static Complete of(Comparison result) {
            int matches = 0;
            int discrepancies = 0;
            int total = 0;

            if (result.getResults() != null) {
                for (var r : result.getResults()) {
                    total++;
                    if ("matched".equals(r.getStatus().value())) {
                        matches++;
                    } else {
                        discrepancies++;
                    }
                }
            }

            return new Complete(result, new ComparisonSummary(matches, discrepancies, total));
        }
    }

    /**
     * Error during comparison.
     *
     * @param message Error description
     * @param code    Optional error code for programmatic handling
     */
    record Error(String message, String code) implements ComparisonEvent {
        public Error(String message) {
            this(message, null);
        }

        public static Error of(String message) {
            return new Error(message, null);
        }

        public static Error of(String message, String code) {
            return new Error(message, code);
        }
    }
}
