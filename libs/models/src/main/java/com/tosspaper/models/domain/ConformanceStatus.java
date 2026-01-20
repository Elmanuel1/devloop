package com.tosspaper.models.domain;

/**
 * Status of document conformance processing.
 */
public enum ConformanceStatus {
    PENDING("pending"),
    PROCESSING("processing"),
    VALIDATED("validated"),
    NEEDS_REVIEW("needs_review"),
    FAILED("failed");
    
    private final String displayName;
    
    ConformanceStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Parse conformance status from string value.
     * Returns PENDING if no match found.
     */
    public static ConformanceStatus fromString(String value) {
        if (value == null) return PENDING;
        for (ConformanceStatus status : values()) {
            if (status.displayName.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return PENDING;
    }
}
