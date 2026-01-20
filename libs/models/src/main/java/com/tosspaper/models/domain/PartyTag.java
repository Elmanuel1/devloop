package com.tosspaper.models.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PartyTag {
    SUPPLIER("supplier"),
    VENDOR("vendor"),
    SHIP_TO("ship_to"),

    BILL_TO("bill to");

    private final String value;

    PartyTag(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @JsonCreator
    public static PartyTag fromValue(String value) {
        for (PartyTag b : PartyTag.values()) {
            if (b.value.equalsIgnoreCase(value)) {
                return b;
            }
        }
        return null;
    }
} 