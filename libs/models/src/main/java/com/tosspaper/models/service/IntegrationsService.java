package com.tosspaper.models.service;

import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Service for managing integration settings and connections.
 */
public interface IntegrationsService {

    /**
     * Get integration settings for a company.
     */
    IntegrationSettings getSettings(Long companyId);

    /**
     * Update integration settings for a company.
     */
    IntegrationSettings updateSettings(Long companyId, IntegrationSettingsUpdate update);

    /**
     * Get all available integration providers.
     */
    List<ProviderInfo> getProviders();

    /**
     * Get OAuth authorization URL for a provider.
     *
     * @param companyId  the company ID
     * @param providerId the provider ID (e.g., "quickbooks")
     */
    OAuthAuthUrl getAuthUrl(Long companyId, String providerId);

    /**
     * Handle OAuth callback from any provider.
     * Provider ID is retrieved from the state token.
     *
     * @param code    the authorization code
     * @param state   the state token
     * @param realmId the provider-specific company ID (realmId for QuickBooks)
     * @param providerId the provider ID from the path
     * @return URI to redirect the user to
     */
    String handleCallback(String code, String state, String realmId, String providerId);

    /**
     * Handle OAuth callback error (e.g., when user denies access).
     * Provider ID is retrieved from the state token.
     *
     * @param error the error code (e.g., "access_denied")
     * @param state the state token
     * @param providerId the provider ID from the path
     * @return URI to redirect the user to
     */
    String handleCallbackError(String error, String state, String providerId);

    /**
     * List all integration connections for a company.
     */
    List<IntegrationConnection> listConnections(Long companyId);

    /**
     * Disconnect an integration.
     */
    void disconnect(String connectionId, Long companyId);

    /**
     * Update connection status (enable/disable sync).
     * Manages Temporal schedule creation/deletion.
     *
     * @param connectionId the connection ID
     * @param companyId    the company ID (for authorization check)
     * @param status      the new status (ENABLED or DISABLED)
     */
    void updateConnectionStatus(String connectionId, Long companyId, IntegrationConnectionStatus status);

    /**
     * Integration settings DTO.
     */
    record IntegrationSettings(
            String currency,
            boolean autoApprovalEnabled,
            BigDecimal autoApprovalThreshold
    ) {}

    /**
     * Update request DTO.
     */
    record IntegrationSettingsUpdate(
            String currency,
            Boolean autoApprovalEnabled,
            BigDecimal autoApprovalThreshold
    ) {}

    /**
     * OAuth authorization URL response.
     */
    record OAuthAuthUrl(
            String authUrl,
            String state
    ) {}

    /**
     * Provider information.
     */
    record ProviderInfo(
            String id,
            String displayName,
            String category
    ) {}
}


