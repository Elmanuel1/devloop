package com.tosspaper.models.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Type of document part that can be compared.
 * Represents the parts we store in the vector store.
 */
@Getter
@RequiredArgsConstructor
public enum DocumentPartType {
    VENDOR_CONTACT("vendor_contact"),
    SHIP_TO_CONTACT("ship_to_contact"),
    LINE_ITEM("line_item");
    
    private final String value;
    
    public static DocumentPartType fromValue(String value) {
        for (DocumentPartType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown document part type: " + value);
    }
}

