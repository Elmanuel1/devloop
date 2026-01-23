package com.tosspaper.integrations.provider;

import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.domain.integration.IntegrationProvider;

/**
 * Provider interface for fetching company information from external systems.
 * Used during OAuth callback to populate externalCompanyName and externalCompanyId.
 */
public interface IntegrationCompanyInfoProvider {

    /**
     * Get the provider ID this implementation handles.
     */
    IntegrationProvider getProviderId();

    /**
     * Fetch company info from the external provider.
     *
     * @param accessToken Valid OAuth access token
     * @param realmId     Provider-specific company identifier (realmId for QuickBooks, tenantId for Xero, etc.)
     * @return Company info with ID, name, and default currency
     */
    CompanyInfo fetchCompanyInfo(String accessToken, String realmId);

    /**
     * Company information from external provider.
     */
    record CompanyInfo(String companyId, String companyName, Currency defaultCurrency) {}
}
