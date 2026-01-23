package com.tosspaper.integrations.provider;

import com.tosspaper.integrations.oauth.OAuthTokens;
import com.tosspaper.models.domain.integration.IntegrationProvider;

/**
 * Interface for OAuth providers (QuickBooks, Xero, Sage, etc.).
 * All providers use OAuth 2.0 Authorization Code Grant flow.
 */
public interface IntegrationOAuthProvider {

    /**
     * Get the provider enum.
     */
    IntegrationProvider getProviderId();

    /**
     * Get the display name (e.g., "QuickBooks Online").
     */
    String getDisplayName();

    /**
     * Build the OAuth authorization URL with the given state.
     *
     * @param state CSRF protection state token
     * @return authorization URL
     */
    String buildAuthorizationUrl(String state);

    /**
     * Exchange authorization code for access and refresh tokens.
     *
     * @param code            the authorization code from callback
     * @param providerCompanyId the provider-specific company ID (realmId for QuickBooks)
     * @param companyId       the local company ID
     * @return OAuth tokens
     */
    OAuthTokens exchangeCodeForTokens(String code, String providerCompanyId, Long companyId);

    /**
     * Refresh access token using refresh token.
     *
     * @param refreshToken the current refresh token
     * @return OAuth tokens
     */
    OAuthTokens refreshToken(String refreshToken);
}
