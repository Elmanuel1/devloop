package com.tosspaper.models.domain;

import lombok.Getter;

/**
 * Enum representing the review status of a document match.
 */
@Getter
public enum ReviewStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    NEEDS_REVIEW("needs_review");
    
    private final String value;
    
    ReviewStatus(String value) {
        this.value = value;
    }
    
    public static ReviewStatus fromValue(String value) {
        for (ReviewStatus status : ReviewStatus.values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown review status: " + value);
    }
}

