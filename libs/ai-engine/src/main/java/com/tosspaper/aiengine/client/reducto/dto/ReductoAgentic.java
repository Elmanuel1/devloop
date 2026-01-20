package com.tosspaper.aiengine.client.reducto.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Agentic settings for Reducto extract request.
 */
@Data
@Builder
public class ReductoAgentic {
    private String scope; // "text"
}
