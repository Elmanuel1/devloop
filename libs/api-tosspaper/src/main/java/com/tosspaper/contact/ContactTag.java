package com.tosspaper.contact;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ContactTag {
    SUPPLIER("supplier"),
    VENDOR("vendor"),
    SHIP_TO("ship_to");

    private final String value;

    ContactTag(String value) {
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
    public static ContactTag fromValue(String value) {
        for (ContactTag b : ContactTag.values()) {
            if (b.value.equalsIgnoreCase(value)) {
                return b;
            }
        }
        return null;
    }
} 