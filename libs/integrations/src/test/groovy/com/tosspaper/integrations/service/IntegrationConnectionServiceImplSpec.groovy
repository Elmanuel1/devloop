package com.tosspaper.integrations.service

import com.tosspaper.integrations.common.util.TokenEncryptionUtil
import com.tosspaper.integrations.config.IntegrationEncryptionProperties
import com.tosspaper.integrations.config.IntegrationProperties
import com.tosspaper.integrations.oauth.OAuthTokens
import com.tosspaper.integrations.provider.IntegrationOAuthProvider
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.repository.IntegrationConnectionRepository
import com.tosspaper.models.domain.integration.IntegrationCategory
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.exception.ForbiddenException
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime
import java.util.Base64

/**
 * Comprehensive tests for IntegrationConnectionServiceImpl.
 * Tests encryption/decryption of OAuth tokens and connection management.
 */
class IntegrationConnectionServiceImplSpec extends Specification {

    IntegrationConnectionRepository connectionRepository = Mock()
    IntegrationEncryptionProperties encryptionProperties = new IntegrationEncryptionProperties()
    IntegrationProviderFactory providerFactory = Mock()
    IntegrationProperties integrationProperties = new IntegrationProperties()

    @Subject
    IntegrationConnectionServiceImpl service

    String encryptionKey

    def setup() {
        // Generate a valid 256-bit encryption key
        encryptionKey = Base64.getEncoder().encodeToString(new byte[32])
        encryptionProperties.key = encryptionKey
        integrationProperties.tokenRefreshThresholdMinutes = 10

        service = new IntegrationConnectionServiceImpl(
            connectionRepository,
            encryptionProperties,
            providerFactory,
            integrationProperties
        )
    }

    private String encrypt(String plaintext) {
        return TokenEncryptionUtil.encrypt(plaintext, encryptionKey)
    }

    def "ensureActiveToken should return connection when token is valid"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .accessToken("valid-token")
            .expiresAt(OffsetDateTime.now().plusHours(2))
            .build()

        when:
        def result = service.ensureActiveToken(connection)

        then:
        result == connection
        0 * providerFactory._
    }

    def "ensureActiveToken should refresh token when token is expired"() {
        given:
        def expiredConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken("old-token")
            .refreshToken("refresh-token")
            .expiresAt(OffsetDateTime.now().minusHours(1))
            .build()

        // Repository stores encrypted tokens for findById call inside updateTokens
        def storedConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken(encrypt("old-token"))
            .refreshToken(encrypt("refresh-token"))
            .expiresAt(OffsetDateTime.now().minusHours(1))
            .build()

        def oauthProvider = Mock(IntegrationOAuthProvider)
        def newTokens = new OAuthTokens(
            "new-access-token",
            "new-refresh-token",
            OffsetDateTime.now().plusHours(1),
            OffsetDateTime.now().plusDays(100),
            null
        )

        when:
        def result = service.ensureActiveToken(expiredConnection)

        then:
        1 * providerFactory.getOAuthProvider(IntegrationProvider.QUICKBOOKS) >> oauthProvider
        1 * oauthProvider.refreshToken("refresh-token") >> newTokens
        1 * connectionRepository.findById("conn-1") >> storedConnection
        1 * connectionRepository.updateTokens(_, _, _, _, _)
        result.accessToken == "new-access-token"
        result.refreshToken == "new-refresh-token"
    }

    def "ensureActiveToken should preserve refresh token when provider returns null"() {
        given:
        def expiredConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken("old-token")
            .refreshToken("original-refresh-token")
            .refreshTokenExpiresAt(OffsetDateTime.now().plusDays(50))
            .expiresAt(OffsetDateTime.now().minusHours(1))
            .build()

        // Repository stores encrypted tokens for findById call inside updateTokens
        def storedConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken(encrypt("old-token"))
            .refreshToken(encrypt("original-refresh-token"))
            .refreshTokenExpiresAt(OffsetDateTime.now().plusDays(50))
            .expiresAt(OffsetDateTime.now().minusHours(1))
            .build()

        def oauthProvider = Mock(IntegrationOAuthProvider)
        def newTokens = new OAuthTokens(
            "new-access-token",
            null, // Provider doesn't rotate refresh token
            OffsetDateTime.now().plusHours(1),
            null,
            null
        )

        when:
        def result = service.ensureActiveToken(expiredConnection)

        then:
        1 * providerFactory.getOAuthProvider(IntegrationProvider.QUICKBOOKS) >> oauthProvider
        1 * oauthProvider.refreshToken("original-refresh-token") >> newTokens
        1 * connectionRepository.findById("conn-1") >> storedConnection
        1 * connectionRepository.updateTokens(_, _, _, _, _)
        result.refreshToken == "original-refresh-token"
    }

    def "ensureActiveToken should throw exception on refresh failure"() {
        given:
        def expiredConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken("old-token")
            .refreshToken("refresh-token")
            .expiresAt(OffsetDateTime.now().minusHours(1))
            .build()

        def oauthProvider = Mock(IntegrationOAuthProvider)

        when:
        service.ensureActiveToken(expiredConnection)

        then:
        1 * providerFactory.getOAuthProvider(IntegrationProvider.QUICKBOOKS) >> oauthProvider
        1 * oauthProvider.refreshToken("refresh-token") >> { throw new RuntimeException("Refresh failed") }
        thrown(com.tosspaper.integrations.common.exception.IntegrationConnectionException)
    }

    def "findById should return decrypted connection"() {
        given:
        def encryptedConnection = IntegrationConnection.builder()
            .id("conn-1")
            .accessToken(encrypt("plain-access-token"))
            .refreshToken(encrypt("plain-refresh-token"))
            .build()

        when:
        def result = service.findById("conn-1")

        then:
        1 * connectionRepository.findById("conn-1") >> encryptedConnection
        result.id == "conn-1"
        result.accessToken == "plain-access-token"
        result.refreshToken == "plain-refresh-token"
    }

    def "findByCompanyAndProvider should return decrypted connection"() {
        given:
        def encryptedConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken(encrypt("plain-access-token"))
            .refreshToken(encrypt("plain-refresh-token"))
            .build()

        when:
        def result = service.findByCompanyAndProvider(100L, IntegrationProvider.QUICKBOOKS)

        then:
        1 * connectionRepository.findByCompanyAndProvider(100L, IntegrationProvider.QUICKBOOKS) >> Optional.of(encryptedConnection)
        result.isPresent()
        result.get().id == "conn-1"
    }

    def "listByCompany should return list of decrypted connections"() {
        given:
        def encryptedConnection1 = IntegrationConnection.builder()
            .id("conn-1")
            .accessToken(encrypt("plain-1"))
            .refreshToken(encrypt("plain-refresh-1"))
            .build()

        def encryptedConnection2 = IntegrationConnection.builder()
            .id("conn-2")
            .accessToken(encrypt("plain-2"))
            .refreshToken(encrypt("plain-refresh-2"))
            .build()

        when:
        def result = service.listByCompany(100L)

        then:
        1 * connectionRepository.findByCompanyId(100L) >> [encryptedConnection1, encryptedConnection2]
        result.size() == 2
    }

    def "create should encrypt tokens before storing"() {
        given:
        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken("plain-access-token")
            .refreshToken("plain-refresh-token")
            .build()

        when:
        def result = service.create(connection)

        then:
        1 * connectionRepository.create(_) >> { IntegrationConnection conn ->
            assert conn.accessToken != "plain-access-token"
            assert conn.refreshToken != "plain-refresh-token"
            // Return the encrypted connection as the repository would
            return conn.toBuilder().id("conn-1").build()
        }
        result.id == "conn-1"
        result.accessToken == "plain-access-token"
        result.refreshToken == "plain-refresh-token"
    }

    def "updateTokens should encrypt tokens before updating"() {
        given:
        def existingConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .accessToken(encrypt("old-access-token"))
            .refreshToken(encrypt("old-refresh-token"))
            .build()

        def newTokens = new OAuthTokens(
            "new-access-token",
            "new-refresh-token",
            OffsetDateTime.now().plusHours(1),
            OffsetDateTime.now().plusDays(100),
            null
        )

        when:
        service.updateTokens(100L, "conn-1", newTokens)

        then:
        1 * connectionRepository.findById("conn-1") >> existingConnection
        1 * connectionRepository.updateTokens("conn-1", _, _, _, _) >> { String id, String accessToken, String refreshToken, OffsetDateTime expiresAt, OffsetDateTime refreshTokenExpiresAt ->
            assert accessToken != "new-access-token"
            assert refreshToken != "new-refresh-token"
        }
    }

    def "updateTokens should throw exception when company doesn't match"() {
        given:
        def existingConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(999L)
            .accessToken(encrypt("some-token"))
            .refreshToken(encrypt("some-refresh"))
            .build()

        def newTokens = new OAuthTokens(
            "new-access-token",
            "new-refresh-token",
            OffsetDateTime.now().plusHours(1),
            OffsetDateTime.now().plusDays(100),
            null
        )

        when:
        service.updateTokens(100L, "conn-1", newTokens)

        then:
        1 * connectionRepository.findById("conn-1") >> existingConnection
        thrown(ForbiddenException)
    }

    def "disconnect should verify company ownership and update status"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .accessToken(encrypt("plain-token"))
            .refreshToken(encrypt("plain-refresh"))
            .build()

        when:
        service.disconnect("conn-1", 100L)

        then:
        1 * connectionRepository.findById("conn-1") >> connection
        1 * connectionRepository.updateStatus("conn-1", IntegrationConnectionStatus.DISABLED, null)
    }

    def "disconnect should throw exception when company doesn't match"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(999L)
            .accessToken(encrypt("some-token"))
            .refreshToken(encrypt("some-refresh"))
            .build()

        when:
        service.disconnect("conn-1", 100L)

        then:
        1 * connectionRepository.findById("conn-1") >> connection
        thrown(ForbiddenException)
    }

    def "updateStatus should verify company ownership and update status"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .accessToken(encrypt("plain-token"))
            .refreshToken(encrypt("plain-refresh"))
            .build()

        def updatedConnection = connection.toBuilder()
            .status(IntegrationConnectionStatus.ENABLED)
            .build()

        when:
        def result = service.updateStatus("conn-1", 100L, IntegrationConnectionStatus.ENABLED)

        then:
        1 * connectionRepository.findById("conn-1") >> connection
        1 * connectionRepository.updateStatus("conn-1", IntegrationConnectionStatus.ENABLED, null) >> updatedConnection
        result.status == IntegrationConnectionStatus.ENABLED
    }

    def "updateStatus should throw exception for non-ENABLED/DISABLED status"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .accessToken(encrypt("plain-token"))
            .refreshToken(encrypt("plain-refresh"))
            .build()

        when:
        service.updateStatus("conn-1", 100L, IntegrationConnectionStatus.EXPIRED)

        then:
        1 * connectionRepository.findById("conn-1") >> connection
        thrown(ForbiddenException)
    }

    def "updateCurrencySettings should update connection preferences"() {
        when:
        service.updateCurrencySettings("conn-1", com.tosspaper.models.domain.Currency.USD, true)

        then:
        1 * connectionRepository.updatePreferences("conn-1", _) >> { String id, com.tosspaper.models.domain.integration.Preferences prefs ->
            assert prefs.defaultCurrency == com.tosspaper.models.domain.Currency.USD
            assert prefs.multicurrencyEnabled == true
        }
    }

    def "findActiveByCompanyAndCategory should return decrypted connection"() {
        given:
        def encryptedConnection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .status(IntegrationConnectionStatus.ENABLED)
            .category(IntegrationCategory.ACCOUNTING)
            .accessToken(encrypt("plain-token"))
            .refreshToken(encrypt("plain-refresh"))
            .build()

        when:
        def result = service.findActiveByCompanyAndCategory(100L, IntegrationCategory.ACCOUNTING)

        then:
        1 * connectionRepository.findActiveByCompanyAndCategory(100L, IntegrationCategory.ACCOUNTING) >> Optional.of(encryptedConnection)
        result.isPresent()
        result.get().id == "conn-1"
    }
}
