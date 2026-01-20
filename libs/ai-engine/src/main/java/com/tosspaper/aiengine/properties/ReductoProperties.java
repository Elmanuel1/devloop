package com.tosspaper.aiengine.properties;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Configuration properties for Reducto AI integration.
 * Extends AIProviderProperties for common settings.
 */
@Data
@NoArgsConstructor
public class ReductoProperties {
    // Currently no Reducto-specific properties
    // Common properties (apiKey, baseUrl) inherited from AIProviderProperties
}
