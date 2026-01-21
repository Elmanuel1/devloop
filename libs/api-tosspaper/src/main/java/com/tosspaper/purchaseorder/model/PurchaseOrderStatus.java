package com.tosspaper.purchaseorder.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum PurchaseOrderStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    CANCELLED("cancelled"),
    COMPLETED("completed");

    private final String value;

    PurchaseOrderStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PurchaseOrderStatus fromValue(String value) {
        return Arrays.stream(PurchaseOrderStatus.values())
                .filter(status -> status.value.equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
    }
} 