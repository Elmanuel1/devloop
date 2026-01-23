package com.tosspaper.integrations.quickbooks.auth;

import com.intuit.oauth2.client.OAuth2PlatformClient;
import com.intuit.oauth2.data.BearerTokenResponse;
import com.intuit.oauth2.exception.OAuthException;
import com.tosspaper.integrations.common.exception.IntegrationAuthException;
import com.tosspaper.integrations.oauth.OAuthStateService;
import com.tosspaper.integrations.oauth.OAuthTokens;
import com.tosspaper.integrations.provider.IntegrationOAuthProvider;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * QuickBooks OAuth provider implementation.
 * Handles QuickBooks Online OAuth2 flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickBooksOAuthProvider implements IntegrationOAuthProvider {

    /**
     * QuickBooks refresh tokens expire after 100 days.
     * @see <a href="https://developer.intuit.com/app/developer/qbo/docs/develop/authentication-and-authorization/oauth-2.0">QuickBooks OAuth 2.0</a>
     */
    private static final int REFRESH_TOKEN_EXPIRY_DAYS = 100;

    private final QuickBooksProperties properties;
    private final OAuth2PlatformClient oauthClient;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public String getDisplayName() {
        return "QuickBooks Online";
    }

    @Override
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://appcenter.intuit.com/connect/oauth2")
                .queryParam("client_id", properties.getClientId())
                .queryParam("response_type", "code")
                .queryParam("scope", URLEncoder.encode(properties.getScopes(), StandardCharsets.UTF_8))
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }


    @Override
    @SneakyThrows
    public OAuthTokens exchangeCodeForTokens(String code, String providerCompanyId, Long companyId) {
        try {
            log.info("Exchanging authorization code for tokens: realmId={}, companyId={}", providerCompanyId, companyId);

            BearerTokenResponse bearerTokenResponse = oauthClient.retrieveBearerTokens(code, properties.getRedirectUri());

            log.info("Bearer token response: tokenType={}, expiresIn={}, refreshTokenExpiresIn={}, scope={}",
                    bearerTokenResponse.getTokenType(),
                    bearerTokenResponse.getExpiresIn(),
                    bearerTokenResponse.getXRefreshTokenExpiresIn(),
                    bearerTokenResponse.getIdToken() != null ? "[present]" : "[absent]");
            OffsetDateTime now = OffsetDateTime.now();
            return new OAuthTokens(
                    bearerTokenResponse.getAccessToken(),
                    bearerTokenResponse.getRefreshToken(),
                    now.plusSeconds(bearerTokenResponse.getExpiresIn().intValue()).minusMinutes(10),
                    now.plusSeconds(bearerTokenResponse.getXRefreshTokenExpiresIn()).minusMinutes(10),
                    providerCompanyId
            );

        } catch (OAuthException e) {
            log.error("QuickBooks OAuth error exchanging code: realmId={}, companyId={}", providerCompanyId, companyId, e);
            throw new IntegrationAuthException("QuickBooks OAuth failed: " + e.getMessage(), e);
        }
    }

    @Override
    public OAuthTokens refreshToken(String refreshToken) {
        try {
            log.debug("Refreshing OAuth token");
            BearerTokenResponse bearerTokenResponse = oauthClient.refreshToken(refreshToken);

            OffsetDateTime now = OffsetDateTime.now();
            // QuickBooks issues a new refresh token on each refresh, with a new 100-day expiry
            return new OAuthTokens(
                    bearerTokenResponse.getAccessToken(),
                    bearerTokenResponse.getRefreshToken(),
                    now.plusSeconds(bearerTokenResponse.getExpiresIn().intValue()),
                    now.plusDays(REFRESH_TOKEN_EXPIRY_DAYS),
                    null // Provider company ID usually doesn't change on refresh
            );
        } catch (OAuthException e) {
            log.error("QuickBooks OAuth error refreshing token", e);
            throw new IntegrationAuthException("QuickBooks token refresh failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to refresh token", e);
            throw new IntegrationAuthException("Failed to refresh OAuth token", e);
        }
    }

}
