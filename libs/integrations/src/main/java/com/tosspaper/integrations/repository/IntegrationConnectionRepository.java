package com.tosspaper.integrations.repository;

import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import org.jooq.DSLContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for integration connection operations.
 */
public interface IntegrationConnectionRepository {

    /**
     * Find a connection by ID.
     *
     * @param id the connection ID
     * @return the connection
     * @throws com.tosspaper.models.exception.NotFoundException if connection not found
     */
    IntegrationConnection findById(String id);

    /**
     * Find a connection by company and provider.
     *
     * @param companyId the company ID
     * @param provider  the integration provider
     * @return the connection if found
     */
    Optional<IntegrationConnection> findByCompanyAndProvider(Long companyId, IntegrationProvider provider);

    /**
     * Find a connection by provider company ID and provider.
     * The provider company ID is stored in the realm_id column and represents
     * the provider-specific company identifier (e.g., QuickBooks realmId, Xero tenantId).
     *
     * @param providerCompanyId the provider-specific company ID
     * @param provider the integration provider
     * @return the connection if found
     */
    Optional<IntegrationConnection> findByProviderCompanyIdAndProvider(String providerCompanyId, IntegrationProvider provider);

    /**
     * Find all connections for a company.
     *
     * @param companyId the company ID
     * @return list of connections
     */
    List<IntegrationConnection> findByCompanyId(Long companyId);

    /**
     * Find all active connections for a provider (for scheduled polling).
     *
     * @param provider the integration provider
     * @return list of active connections
     */
    List<IntegrationConnection> findActiveByProvider(IntegrationProvider provider);

    /**
     * Find active (enabled) connection for a company by category.
     *
     * @param companyId the company ID
     * @param category  the category (e.g., FINANCIAL, ACCOUNTING, FILES)
     * @return the active connection if found, empty if not found
     */
    Optional<IntegrationConnection> findActiveByCompanyAndCategory(Long companyId, com.tosspaper.models.domain.integration.IntegrationCategory category);

    /**
     * Create a new connection.
     *
     * @param connection the connection to create
     * @return the created connection with ID
     */
    IntegrationConnection create(IntegrationConnection connection);

    /**
     * Create a new connection within a transaction.
     *
     * @param ctx        the DSL context for transaction
     * @param connection the connection to create
     * @return the created connection with ID
     */
    IntegrationConnection create(DSLContext ctx, IntegrationConnection connection);

    /**
     * Update tokens after refresh.
     *
     * @param id                      the connection ID
     * @param accessToken             new access token
     * @param refreshToken            new refresh token (may be null if not rotated)
     * @param expiresAt               new access token expiry time
     * @param refreshTokenExpiresAt   new refresh token expiry time (null if not provided)
     */
    void updateTokens(String id, String accessToken, String refreshToken, OffsetDateTime expiresAt, OffsetDateTime refreshTokenExpiresAt);

    /**
     * Update connection status.
     *
     * @param id           the connection ID
     * @param status       new status
     * @param errorMessage optional error message (null if not updating error message)
     * @return the updated connection
     */
    IntegrationConnection updateStatus(String id, IntegrationConnectionStatus status, String errorMessage);

    /**
     * Update last sync timestamp.
     *
     * @param id         the connection ID
     * @param lastSyncAt the sync timestamp
     * @return the updated connection
     */
    IntegrationConnection updateLastSyncAt(String id, OffsetDateTime lastSyncAt);

    /**
     * Update preferences from provider Preferences.
     * Stores preferences as separate columns (defaultCurrency, multicurrencyEnabled).
     *
     * @param id         the connection ID
     * @param preferences the preferences object containing defaultCurrency and multicurrencyEnabled
     */
    void updatePreferences(String id, com.tosspaper.models.domain.integration.Preferences preferences);

}
