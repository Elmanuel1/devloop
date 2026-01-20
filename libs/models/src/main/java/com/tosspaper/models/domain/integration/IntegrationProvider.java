package com.tosspaper.models.domain.integration;

import lombok.Getter;

/**
 * Supported financial integration providers.
 */
@Getter
public enum IntegrationProvider {
    QUICKBOOKS("quickbooks", "QuickBooks Online", IntegrationCategory.ACCOUNTING),
    XERO("xero", "Xero", IntegrationCategory.ACCOUNTING),
    SAGE("sage", "Sage", IntegrationCategory.ACCOUNTING);

    private final String value;
    private final String displayName;
    private final IntegrationCategory category;

    IntegrationProvider(String value, String displayName, IntegrationCategory category) {
        this.value = value;
        this.displayName = displayName;
        this.category = category;
    }

    public static IntegrationProvider fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (IntegrationProvider provider : IntegrationProvider.values()) {
            if (provider.value.equalsIgnoreCase(value) || provider.name().equalsIgnoreCase(value)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown integration provider: " + value);
    }
}
