package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reducto parsing configuration.
 * Contains parsing-specific settings like enhancement options and timeout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoParsing {
    private ReductoEnhance enhance;
    private ReductoRetrieval retrieval;
    private ReductoFormatting formatting;
    private ReductoSpreadsheet spreadsheet;
    private ReductoParseSettings settings;
}
