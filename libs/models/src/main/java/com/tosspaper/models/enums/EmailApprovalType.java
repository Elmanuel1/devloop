package com.tosspaper.models.enums;

public enum EmailApprovalType {
    APPROVED("approved"),
    REJECT("reject");

    private final String value;

    EmailApprovalType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static EmailApprovalType fromString(String value) {
        for (EmailApprovalType type : EmailApprovalType.values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid approval type: " + value);
    }
}

