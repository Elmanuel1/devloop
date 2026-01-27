package com.tosspaper.aiengine.agent;

/**
 * Summary statistics for a comparison result.
 *
 * @param matches       Number of parts that matched
 * @param discrepancies Number of parts with discrepancies
 * @param total         Total number of parts compared
 */
public record ComparisonSummary(
    int matches,
    int discrepancies,
    int total
) {
    /**
     * Get the match percentage (0-100).
     */
    public double matchPercentage() {
        if (total == 0) return 0;
        return (matches * 100.0) / total;
    }

    /**
     * Check if all parts matched.
     */
    public boolean isFullMatch() {
        return discrepancies == 0 && total > 0;
    }

    /**
     * Get a human-readable summary string.
     */
    public String toDisplayString() {
        return String.format("%d matches, %d discrepancies", matches, discrepancies);
    }
}
