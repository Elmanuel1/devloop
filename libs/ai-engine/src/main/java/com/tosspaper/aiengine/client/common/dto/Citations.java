package com.tosspaper.aiengine.client.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic citations settings for AI extraction requests.
 * Used across different AI providers (Chunkr, Reducto, etc.).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Citations {
    private Boolean enabled;
}
