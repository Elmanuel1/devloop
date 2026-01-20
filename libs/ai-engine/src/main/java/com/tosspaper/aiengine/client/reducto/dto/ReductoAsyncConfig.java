package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reducto async configuration.
 * Contains async processing settings like priority and webhook configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoAsyncConfig {
    private Boolean priority;
    private Object metadata;
    private ReductoWebhookConfig webhook; 
}
