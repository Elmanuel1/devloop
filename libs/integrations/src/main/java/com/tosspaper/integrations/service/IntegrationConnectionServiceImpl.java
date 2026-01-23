package com.tosspaper.integrations.service;

import com.tosspaper.integrations.common.exception.IntegrationConnectionException;
import com.tosspaper.integrations.common.util.TokenEncryptionUtil;
import com.tosspaper.integrations.config.IntegrationEncryptionProperties;
import com.tosspaper.integrations.config.IntegrationProperties;
import com.tosspaper.integrations.oauth.OAuthTokens;
import com.tosspaper.integrations.provider.IntegrationOAuthProvider;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.repository.IntegrationConnectionRepository;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Implementation of IntegrationConnectionService.
 * Handles encryption/decryption of OAuth tokens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationConnectionServiceImpl implements IntegrationConnectionService {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationEncryptionProperties encryptionProperties;
    private final IntegrationProviderFactory providerFactory;
    private final IntegrationProperties integrationProperties;

    @Override
    public IntegrationConnection ensureActiveToken(IntegrationConnection connection) {
        if (connection.isTokenValid()) {
            return connection;
        }

        log.info("Refreshing tokens for connection: id={}, provider={}", connection.getId(), connection.getProvider());

        try {
            IntegrationOAuthProvider oauthProvider = providerFactory.getOAuthProvider(connection.getProvider());
            OAuthTokens tokens = oauthProvider.refreshToken(connection.getRefreshToken());

            // Calculate new expiration with buffer
            int bufferMinutes = integrationProperties.getTokenRefreshThresholdMinutes();
            OffsetDateTime expiresAt = tokens.expiresAt().minusMinutes(bufferMinutes);

            // Preserve existing refresh token if provider doesn't rotate it (returns null)
            String effectiveRefreshToken = tokens.refreshToken() != null
                    ? tokens.refreshToken()
                    : connection.getRefreshToken();
            OffsetDateTime effectiveRefreshTokenExpiresAt = tokens.refreshTokenExpiresAt() != null
                    ? tokens.refreshTokenExpiresAt()
                    : connection.getRefreshTokenExpiresAt();

            OAuthTokens adjustedTokens = new OAuthTokens(
                    tokens.accessToken(),
                    effectiveRefreshToken,
                    expiresAt,
                    effectiveRefreshTokenExpiresAt,
                    tokens.providerCompanyId()
            );

            // Update in DB
            updateTokens(connection.getCompanyId(), connection.getId(), adjustedTokens);

            // Return updated connection
            return connection.toBuilder()
                    .accessToken(tokens.accessToken())
                    .refreshToken(effectiveRefreshToken)
                    .expiresAt(expiresAt)
                    .refreshTokenExpiresAt(effectiveRefreshTokenExpiresAt)
                    .build();

        } catch (Exception e) {
            log.error("Failed to refresh token for connection: {}", connection.getId(), e);
            throw new IntegrationConnectionException("Token refresh failed", e);
        }
    }

    @Override
    public IntegrationConnection findById(String id) {
        IntegrationConnection connection = connectionRepository.findById(id);
        return decryptTokens(connection);
    }

    @Override
    public Optional<IntegrationConnection> findByCompanyAndProvider(Long companyId, IntegrationProvider provider) {
        return connectionRepository.findByCompanyAndProvider(companyId, provider)
                .map(this::decryptTokens);
    }

    @Override
    public Optional<IntegrationConnection> findByProviderCompanyIdAndProvider(String providerCompanyId, IntegrationProvider provider) {
        return connectionRepository.findByProviderCompanyIdAndProvider(providerCompanyId, provider)
                .map(this::decryptTokens);
    }

    @Override
    public List<IntegrationConnection> listByCompany(Long companyId) {
        return connectionRepository.findByCompanyId(companyId).stream()
                .map(this::decryptTokens)
                .collect(Collectors.toList());
    }

    @Override
    public java.util.Optional<IntegrationConnection> findActiveByCompanyAndCategory(Long companyId, com.tosspaper.models.domain.integration.IntegrationCategory category) {
        return connectionRepository.findActiveByCompanyAndCategory(companyId, category)
                .map(this::decryptTokens);
    }

    @Override
    public IntegrationConnection create(IntegrationConnection connection) {
        // Encrypt tokens before storing
        IntegrationConnection encrypted = encryptTokens(connection);
        IntegrationConnection created = connectionRepository.create(encrypted);
        return decryptTokens(created);
    }

    @Override
    public void updateTokens(Long companyId, String connectionId, OAuthTokens tokens) {
        IntegrationConnection connection = findById(connectionId);
        if (!connection.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(
                    "Integration connection does not belong to company: " + companyId);
        }
        // Encrypt tokens before storing
        String encryptedAccessToken = TokenEncryptionUtil.encrypt(tokens.accessToken(), encryptionProperties.getKey());
        String encryptedRefreshToken = tokens.refreshToken() != null
                ? TokenEncryptionUtil.encrypt(tokens.refreshToken(), encryptionProperties.getKey())
                : null;
        connectionRepository.updateTokens(connectionId, encryptedAccessToken, encryptedRefreshToken,
                tokens.expiresAt(), tokens.refreshTokenExpiresAt());
    }

    @Override
    public void disconnect(String connectionId, Long companyId) {
        IntegrationConnection connection = findById(connectionId);
        
        // Verify company ownership
        if (!connection.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(
                    "Integration connection does not belong to company: " + companyId);
        }

        connectionRepository.updateStatus(connectionId, IntegrationConnectionStatus.DISABLED, null);
        log.info("Disconnected integration: id={}, companyId={}, provider={}",
                connectionId, companyId, connection.getProvider());
    }

    @Override
    public IntegrationConnection updateStatus(String connectionId, Long companyId, IntegrationConnectionStatus status) {
        IntegrationConnection connection = findById(connectionId);

        // Verify company ownership
        if (!connection.getCompanyId().equals(companyId)) {
            throw new ForbiddenException(
                    "Integration connection does not belong to company: " + companyId);
        }

        // Only allow ENABLED and DISABLED via API
        if (status != IntegrationConnectionStatus.ENABLED && status != IntegrationConnectionStatus.DISABLED) {
            throw new ForbiddenException("Only 'enabled' and 'disabled' status values are allowed via API");
        }

        IntegrationConnection updated = connectionRepository.updateStatus(connectionId, status, null);
        log.info("Updated connection status: id={}, companyId={}, status={}",
                connectionId, companyId, status);
        return updated;
    }

    @Override
    public void updateCurrencySettings(String connectionId, com.tosspaper.models.domain.Currency defaultCurrency, Boolean multicurrencyEnabled) {
        log.info("Updating currency settings for connection {}: defaultCurrency={}, multicurrencyEnabled={}",
                connectionId, defaultCurrency, multicurrencyEnabled);
        connectionRepository.updatePreferences(connectionId,
                com.tosspaper.models.domain.integration.Preferences.builder()
                        .defaultCurrency(defaultCurrency)
                        .multicurrencyEnabled(multicurrencyEnabled)
                        .build());
    }

    /**
     * Encrypt tokens in a connection before storing in repository.
     */
    private IntegrationConnection encryptTokens(IntegrationConnection connection) {
        try {
            String encryptedAccessToken = TokenEncryptionUtil.encrypt(
                    connection.getAccessToken(),
                    encryptionProperties.getKey()
            );
            String encryptedRefreshToken = TokenEncryptionUtil.encrypt(
                    connection.getRefreshToken(),
                    encryptionProperties.getKey()
            );

            return connection.toBuilder()
                    .accessToken(encryptedAccessToken)
                    .refreshToken(encryptedRefreshToken)
                    .build();
        } catch (Exception e) {
            throw new IntegrationConnectionException("Failed to encrypt tokens", e);
        }
    }

    /**
     * Decrypt tokens in a connection after retrieving from repository.
     */
    private IntegrationConnection decryptTokens(IntegrationConnection connection) {
        try {
            String decryptedAccessToken = TokenEncryptionUtil.decrypt(
                    connection.getAccessToken(),
                    encryptionProperties.getKey()
            );
            String decryptedRefreshToken = connection.getRefreshToken() != null
                    ? TokenEncryptionUtil.decrypt(connection.getRefreshToken(), encryptionProperties.getKey())
                    : null;

            return connection.toBuilder()
                    .accessToken(decryptedAccessToken)
                    .refreshToken(decryptedRefreshToken)
                    .build();
        } catch (Exception e) {
            log.error("Failed to decrypt tokens for connection: id={}", connection.getId(), e);
            throw new IntegrationConnectionException("Failed to decrypt tokens", e);
        }
    }
}
