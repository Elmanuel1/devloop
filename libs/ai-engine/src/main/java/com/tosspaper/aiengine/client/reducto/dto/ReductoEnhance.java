package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Enhancement settings for Reducto extract request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoEnhance {
    private ReductoAgentic[] agentic;
    private Boolean summarizeFigures;
}
