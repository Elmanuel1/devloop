package com.tosspaper.aiengine.client.reducto.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reducto webhook configuration.
 * Supports both direct and Svix webhook modes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReductoWebhookConfig {
    private String mode; // "direct" or "svix"
    private String url; // For direct mode
    private String[] channels; // For svix mode
}
