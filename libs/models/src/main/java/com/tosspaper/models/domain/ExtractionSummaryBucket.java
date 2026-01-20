package com.tosspaper.models.domain;

import lombok.Getter;

/**
 * Dashboard bucket classification for extraction summary listings.
 */
@Getter
public enum ExtractionSummaryBucket {
    IN_PROGRESS("in_progress"),
    FAILED("failed"),
    NEEDS_ATTENTION("needs_attention"),
    REJECTED("rejected"),
    SUCCESSFUL_AWAITING_REVIEW("successful_awaiting_review"),
    SUCCESS_APPROVED("success_approved");

    private final String value;

    ExtractionSummaryBucket(String value) {
        this.value = value;
    }

    public static ExtractionSummaryBucket fromValue(String value) {
        for (ExtractionSummaryBucket bucket : values()) {
            if (bucket.value.equalsIgnoreCase(value)) {
                return bucket;
            }
        }
        throw new IllegalArgumentException("Unknown extraction summary bucket: " + value);
    }
}

