package com.tosspaper.aiengine.client.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic settings for AI extraction requests.
 * Used across different AI providers (Chunkr, Reducto, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Settings {
    private Citations citations;
    
    @JsonProperty("array_extract")
    private Boolean arrayExtract;
    // Can add other common settings here as needed
}
