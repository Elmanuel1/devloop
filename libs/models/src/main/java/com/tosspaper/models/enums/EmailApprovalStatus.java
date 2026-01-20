package com.tosspaper.models.enums;

public enum EmailApprovalStatus {
    PENDING_APPROVAL("pending_approval"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String value;

    EmailApprovalStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static EmailApprovalStatus fromString(String value) {
        for (EmailApprovalStatus status : EmailApprovalStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid approval status: " + value);
    }
}

