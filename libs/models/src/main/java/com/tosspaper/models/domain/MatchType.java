package com.tosspaper.models.domain;

import lombok.Getter;

/**
 * Enum representing the matching status and how a document match was determined.
 */
@Getter
public enum MatchType {
    // Processing states
    PENDING("pending"),                         // Not yet matched
    IN_PROGRESS("in_progress"),                 // AI actively searching

    // AI match results
    DIRECT("direct"),                           // Matched directly by PO number in document
    AI_MATCH("ai_match"),                       // AI evaluation determined match
    NO_MATCH("no_match"),                       // No match found or determined

    // Human actions
    MANUAL("manual"),                           // Manually linked by user
    NO_PO_REQUIRED("no_po_required");           // User confirmed no PO needed

    private final String value;

    MatchType(String value) {
        this.value = value;
    }

    public static MatchType fromValue(String value) {
        if (value == null) {
            return PENDING;
        }
        for (MatchType type : MatchType.values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown match type: " + value);
    }

    /**
     * Returns true if this match type represents a human decision that should not be overwritten by AI.
     */
    public boolean isHumanDecision() {
        return this == MANUAL || this == NO_PO_REQUIRED;
    }
}

