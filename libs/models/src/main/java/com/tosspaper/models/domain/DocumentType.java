package com.tosspaper.models.domain;

import lombok.Getter;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Document types supported for conformance processing.
 * Each type has a file prefix for resource loading and multiple aliases for classification.
 */
@Getter
public enum DocumentType {
    PURCHASE_ORDER("po"),
    INVOICE("invoice"),
    DELIVERY_SLIP("delivery_slip"),
    DELIVERY_NOTE("delivery_note"),
    UNKNOWN("unknown");
    
    private final String filePrefix;
    DocumentType(String filePrefix) {
        this.filePrefix = filePrefix;

    }


    /**
     * Parse document type from string value by checking aliases.
     * Returns UNKNOWN if no match found.
     */
    public static DocumentType fromString(String value) {
        return Stream.of(values())
                .filter(v -> v.filePrefix.equals(value))
                .findFirst().orElse(UNKNOWN);
    }
}

