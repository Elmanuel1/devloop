package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Reducto parsing settings.
 * Contains parsing-specific configuration like timeout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoParseSettings {
    private Integer timeout; // Timeout in seconds
    private String ocrSystem; // "standard"
    private Boolean forceUrlResult; // false
    private String forceFileExtension; // null
    private Boolean returnOcrData; // false
    private List<String> returnImages; // empty
    private Boolean embedPdfMetadata; // false
    private Boolean persistResults; // true
    private Object pageRange; // null
    private String documentPassword; // null
}
