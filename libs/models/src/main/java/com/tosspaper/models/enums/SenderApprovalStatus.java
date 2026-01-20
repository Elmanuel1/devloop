package com.tosspaper.models.enums;

public enum SenderApprovalStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String value;

    SenderApprovalStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SenderApprovalStatus fromValue(String value) {
        for (SenderApprovalStatus status : SenderApprovalStatus.values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown SenderApprovalStatus value: " + value);
    }
}

