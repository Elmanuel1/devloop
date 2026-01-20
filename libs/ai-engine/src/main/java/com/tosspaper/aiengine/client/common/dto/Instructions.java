package com.tosspaper.aiengine.client.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic instructions for AI extraction requests.
 * Used across different AI providers (Chunkr, Reducto, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Instructions {
    @JsonRawValue
    private String schema; // JSON schema as raw JSON string
    
    @JsonProperty("system_prompt")
    private String systemPrompt; // "system_prompt"
}
