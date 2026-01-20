package com.tosspaper.models.domain.comparison;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * Enum representing the status of a field comparison between PO and document.
 */
@Getter
public enum MatchStatus {
    MATCH("MATCH"),
    MISMATCH("MISMATCH"),
    PO_ONLY("PO_ONLY"),
    DOC_ONLY("DOC_ONLY"),
    NOT_COMPARED("NOT_COMPARED");
    
    private final String value;
    
    MatchStatus(String value) {
        this.value = value;
    }
    
    @JsonValue
    public String getValue() {
        return value;
    }
    
    public static MatchStatus fromValue(String value) {
        for (MatchStatus status : MatchStatus.values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown match status: " + value);
    }
}

