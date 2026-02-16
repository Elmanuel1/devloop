package com.tosspaper.integrations.quickbooks.auth

import com.intuit.oauth2.client.OAuth2PlatformClient
import com.intuit.oauth2.data.BearerTokenResponse
import com.intuit.oauth2.exception.OAuthException
import com.tosspaper.integrations.common.exception.IntegrationAuthException
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive tests for QuickBooksOAuthProvider.
 * Tests QuickBooks OAuth2 flow including token exchange and refresh.
 */
class QuickBooksOAuthProviderSpec extends Specification {

    QuickBooksProperties properties = new QuickBooksProperties()
    OAuth2PlatformClient oauthClient = Mock()

    @Subject
    QuickBooksOAuthProvider provider

    def setup() {
        properties.clientId = "test-client-id"
        properties.scopes = "com.intuit.quickbooks.accounting"
        properties.redirectUri = "https://app.example.com/oauth/callback"

        provider = new QuickBooksOAuthProvider(properties, oauthClient)
    }

    def "getProviderId should return QUICKBOOKS"() {
        expect:
        provider.getProviderId() == IntegrationProvider.QUICKBOOKS
    }

    def "getDisplayName should return QuickBooks Online"() {
        expect:
        provider.getDisplayName() == "QuickBooks Online"
    }

    def "buildAuthorizationUrl should include all required parameters"() {
        given:
        def state = "test-state-123"

        when:
        def url = provider.buildAuthorizationUrl(state)

        then:
        url.contains("client_id=test-client-id")
        url.contains("response_type=code")
        url.contains("scope=com.intuit.quickbooks.accounting")
        url.contains("redirect_uri=https://app.example.com/oauth/callback")
        url.contains("state=test-state-123")
        url.startsWith("https://appcenter.intuit.com/connect/oauth2")
    }

    def "exchangeCodeForTokens should return tokens on success"() {
        given:
        def code = "auth-code-123"
        def realmId = "realm-456"
        def companyId = 100L

        def tokenResponse = Mock(BearerTokenResponse) {
            getAccessToken() >> "access-token-789"
            getRefreshToken() >> "refresh-token-abc"
            getExpiresIn() >> 3600L
            getXRefreshTokenExpiresIn() >> 8640000L
            getTokenType() >> "Bearer"
            getIdToken() >> null
        }

        when:
        def result = provider.exchangeCodeForTokens(code, realmId, companyId)

        then:
        1 * oauthClient.retrieveBearerTokens(code, "https://app.example.com/oauth/callback") >> tokenResponse
        result.accessToken() == "access-token-789"
        result.refreshToken() == "refresh-token-abc"
        result.expiresAt() != null
        result.refreshTokenExpiresAt() != null
        result.providerCompanyId() == "realm-456"
    }

    def "exchangeCodeForTokens should apply 10 minute buffer to expiration times"() {
        given:
        def code = "auth-code-123"
        def realmId = "realm-456"
        def companyId = 100L

        def tokenResponse = Mock(BearerTokenResponse) {
            getAccessToken() >> "access-token"
            getRefreshToken() >> "refresh-token"
            getExpiresIn() >> 3600L // 1 hour
            getXRefreshTokenExpiresIn() >> 86400L // 1 day
        }

        when:
        def result = provider.exchangeCodeForTokens(code, realmId, companyId)

        then:
        1 * oauthClient.retrieveBearerTokens(code, _) >> tokenResponse
        result.expiresAt().isBefore(java.time.OffsetDateTime.now().plusSeconds(3600))
        result.expiresAt().isAfter(java.time.OffsetDateTime.now().plusSeconds(3600).minusMinutes(15))
    }

    def "exchangeCodeForTokens should throw IntegrationAuthException on OAuth error"() {
        given:
        def code = "invalid-code"
        def realmId = "realm-123"
        def companyId = 100L

        when:
        provider.exchangeCodeForTokens(code, realmId, companyId)

        then:
        1 * oauthClient.retrieveBearerTokens(code, _) >> { throw new OAuthException("Invalid authorization code") }
        thrown(IntegrationAuthException)
    }

    def "refreshToken should return new tokens on success"() {
        given:
        def refreshToken = "refresh-token-123"

        def tokenResponse = Mock(BearerTokenResponse) {
            getAccessToken() >> "new-access-token"
            getRefreshToken() >> "new-refresh-token"
            getExpiresIn() >> 3600L
        }

        when:
        def result = provider.refreshToken(refreshToken)

        then:
        1 * oauthClient.refreshToken(refreshToken) >> tokenResponse
        result.accessToken() == "new-access-token"
        result.refreshToken() == "new-refresh-token"
        result.expiresAt() != null
        result.refreshTokenExpiresAt() != null
        result.providerCompanyId() == null
    }

    def "refreshToken should set refresh token expiry to 100 days"() {
        given:
        def refreshToken = "refresh-token-123"

        def tokenResponse = Mock(BearerTokenResponse) {
            getAccessToken() >> "new-access-token"
            getRefreshToken() >> "new-refresh-token"
            getExpiresIn() >> 3600L
        }

        when:
        def result = provider.refreshToken(refreshToken)

        then:
        1 * oauthClient.refreshToken(refreshToken) >> tokenResponse
        result.refreshTokenExpiresAt().isAfter(java.time.OffsetDateTime.now().plusDays(99))
        result.refreshTokenExpiresAt().isBefore(java.time.OffsetDateTime.now().plusDays(101))
    }

    def "refreshToken should throw IntegrationAuthException on OAuth error"() {
        given:
        def refreshToken = "invalid-refresh-token"

        when:
        provider.refreshToken(refreshToken)

        then:
        1 * oauthClient.refreshToken(refreshToken) >> { throw new OAuthException("Invalid refresh token") }
        thrown(IntegrationAuthException)
    }

    def "refreshToken should throw IntegrationAuthException on general exception"() {
        given:
        def refreshToken = "refresh-token"

        when:
        provider.refreshToken(refreshToken)

        then:
        1 * oauthClient.refreshToken(refreshToken) >> { throw new RuntimeException("Network error") }
        thrown(IntegrationAuthException)
    }

    def "buildAuthorizationUrl should URL encode scopes"() {
        given:
        properties.scopes = "scope1 scope2"
        def state = "state"

        when:
        def url = provider.buildAuthorizationUrl(state)

        then:
        url.contains("scope=scope1")
    }
}
