package com.tosspaper.integrations.service;

import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.IntegrationCategory;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing integration connections.
 */
public interface IntegrationConnectionService {

    /**
     * Ensure the connection has a valid access token.
     * Refreshes the token if expired or about to expire.
     *
     * @param connection the connection to check
     * @return the connection with valid tokens (updated if necessary)
     */
    IntegrationConnection ensureActiveToken(IntegrationConnection connection);

    /**
     * Find a connection by ID.
     *
     * @param id the connection ID
     * @return the connection if found
     */
    IntegrationConnection findById(String id);

    /**
     * Find a connection by company and provider.
     *
     * @param companyId the company ID
     * @param provider  the integration provider
     * @return the connection if found, empty if not found
     */
    Optional<IntegrationConnection> findByCompanyAndProvider(Long companyId, IntegrationProvider provider);

    /**
     * Find a connection by provider company ID and provider.
     * The provider company ID is stored in the realm_id column and represents
     * the provider-specific company identifier (e.g., QuickBooks realmId, Xero tenantId).
     *
     * @param providerCompanyId the provider-specific company ID
     * @param provider the integration provider
     * @return the connection if found, empty if not found
     */
    Optional<IntegrationConnection> findByProviderCompanyIdAndProvider(String providerCompanyId, IntegrationProvider provider);

    /**
     * List all connections for a company.
     *
     * @param companyId the company ID
     * @return list of connections
     */
    List<IntegrationConnection> listByCompany(Long companyId);

    /**
     * Find active (enabled) connection for a company by category.
     *
     * @param companyId the company ID
     * @param category  the category (e.g., FINANCIAL, ACCOUNTING, FILES)
     * @return the active connection if found, empty if not found
     */
    java.util.Optional<IntegrationConnection> findActiveByCompanyAndCategory(Long companyId, IntegrationCategory category);

    /**
     * Create a new connection.
     *
     * @param connection the connection to create
     * @return the created connection
     */
    IntegrationConnection create(IntegrationConnection connection);

    /**
     * Update connection tokens after refresh.
     *
     * @param companyId    the company ID (for authorization check)
     * @param connectionId the connection ID
     * @param tokens      the OAuth tokens
     */
    void updateTokens(Long companyId, String connectionId, com.tosspaper.integrations.oauth.OAuthTokens tokens);

    /**
     * Disconnect an integration (delete connection).
     *
     * @param connectionId the connection ID
     * @param companyId    the company ID (for authorization check)
     */
    void disconnect(String connectionId, Long companyId);

    /**
     * Update connection status (enable/disable sync).
     * Only ENABLED and DISABLED status values are allowed via API.
     *
     * @param connectionId the connection ID
     * @param companyId    the company ID (for authorization check)
     * @param status       the new status (ENABLED or DISABLED)
     * @return the updated connection
     */
    IntegrationConnection updateStatus(String connectionId, Long companyId, IntegrationConnectionStatus status);

    /**
     * Update connection currency settings from QuickBooks preferences.
     *
     * @param connectionId the connection ID
     * @param defaultCurrency the default currency from QuickBooks
     * @param multicurrencyEnabled whether multicurrency is enabled
     */
    void updateCurrencySettings(String connectionId, com.tosspaper.models.domain.Currency defaultCurrency, Boolean multicurrencyEnabled);
}
