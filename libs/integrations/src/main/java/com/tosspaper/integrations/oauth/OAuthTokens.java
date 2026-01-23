package com.tosspaper.integrations.oauth;

import java.time.OffsetDateTime;

/**
 * OAuth tokens from provider.
 * Contains access token, refresh token, and expiry information.
 */
public record OAuthTokens(
        String accessToken,
        String refreshToken,
        OffsetDateTime expiresAt,
        OffsetDateTime refreshTokenExpiresAt,  // QuickBooks: 100 days from issuance
        String providerCompanyId  // realmId for QuickBooks, tenantId for Xero, etc.
) {
}


