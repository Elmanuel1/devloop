package com.tosspaper.models.domain.integration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Categories for integration connections.
 * Only one connection per category can be enabled at a time.
 */
@Getter
public enum IntegrationCategory {
    ACCOUNTING("accounting", "Accounting");

    private final String value;
    private final String displayName;

    IntegrationCategory(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static IntegrationCategory fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.toLowerCase().trim();
        for (IntegrationCategory category : values()) {
            if (category.value.equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
