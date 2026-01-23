package com.tosspaper.integrations.repository.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.integrations.common.exception.IntegrationConnectionException;
import com.tosspaper.integrations.repository.IntegrationConnectionRepository;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.exception.DuplicateException;
import com.tosspaper.models.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.exception.NoDataFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.INTEGRATION_CONNECTIONS;

/**
 * JOOQ implementation for integration connection repository operations.
 * Stores and retrieves encrypted tokens as-is (encryption handled in the service layer).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class IntegrationConnectionRepositoryImpl implements IntegrationConnectionRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    public IntegrationConnection findById(String id) {
        try {
            var record = dsl.selectFrom(INTEGRATION_CONNECTIONS)
                    .where(INTEGRATION_CONNECTIONS.ID.eq(id))
                    .fetchSingle();
            return toDomain(record);
        } catch (NoDataFoundException e) {
            throw new NotFoundException("Integration connection not found: " + id);
        }
    }

    @Override
    public Optional<IntegrationConnection> findByCompanyAndProvider(Long companyId, IntegrationProvider provider) {
        return dsl.selectFrom(INTEGRATION_CONNECTIONS)
                .where(INTEGRATION_CONNECTIONS.COMPANY_ID.eq(companyId))
                .and(INTEGRATION_CONNECTIONS.PROVIDER.eq(provider.name()))
                .orderBy(INTEGRATION_CONNECTIONS.CREATED_AT.desc())
                .fetchOptional()
                .map(this::toDomain);
    }

    @Override
    public Optional<IntegrationConnection> findByProviderCompanyIdAndProvider(String providerCompanyId, IntegrationProvider provider) {
        return dsl.selectFrom(INTEGRATION_CONNECTIONS)
                .where(INTEGRATION_CONNECTIONS.REALM_ID.eq(providerCompanyId))
                .and(INTEGRATION_CONNECTIONS.PROVIDER.eq(provider.name()))
                .fetchOptional()
                .map(this::toDomain);
    }

    @Override
    public List<IntegrationConnection> findByCompanyId(Long companyId) {
        return dsl.selectFrom(INTEGRATION_CONNECTIONS)
                .where(INTEGRATION_CONNECTIONS.COMPANY_ID.eq(companyId))
                .orderBy(INTEGRATION_CONNECTIONS.CREATED_AT.desc())
                .fetch()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<IntegrationConnection> findActiveByProvider(IntegrationProvider provider) {
        return dsl.selectFrom(INTEGRATION_CONNECTIONS)
                .where(INTEGRATION_CONNECTIONS.PROVIDER.eq(provider.name()))
                .and(INTEGRATION_CONNECTIONS.STATUS.eq(IntegrationConnectionStatus.ENABLED.getValue()))
                .orderBy(INTEGRATION_CONNECTIONS.CREATED_AT.desc())
                .fetch()
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<IntegrationConnection> findActiveByCompanyAndCategory(Long companyId, com.tosspaper.models.domain.integration.IntegrationCategory category) {
        if (category == null) {
            return Optional.empty();
        }
        return dsl.selectFrom(INTEGRATION_CONNECTIONS)
                .where(INTEGRATION_CONNECTIONS.COMPANY_ID.eq(companyId))
                .and(INTEGRATION_CONNECTIONS.STATUS.eq(IntegrationConnectionStatus.ENABLED.getValue()))
                .and(INTEGRATION_CONNECTIONS.CATEGORY.eq(category.getValue()))
                .orderBy(INTEGRATION_CONNECTIONS.CREATED_AT.desc())
                .fetchOptional()
                .map(this::toDomain);
    }

    @Override
    public IntegrationConnection create(IntegrationConnection connection) {
        return create(dsl, connection);
    }

    @Override
    @SneakyThrows
    public IntegrationConnection create(DSLContext ctx, IntegrationConnection connection) {
        try {
            // Note: Tokens should already be encrypted by the service layer before calling this method
            // Repository stores tokens as-is (encrypted strings)

            var record = ctx.insertInto(INTEGRATION_CONNECTIONS)
                    .set(INTEGRATION_CONNECTIONS.COMPANY_ID, connection.getCompanyId())
                    .set(INTEGRATION_CONNECTIONS.PROVIDER, connection.getProvider().name())
                    .set(INTEGRATION_CONNECTIONS.STATUS, connection.getStatus().getValue())
                    .set(INTEGRATION_CONNECTIONS.ACCESS_TOKEN, connection.getAccessToken()) // Already encrypted
                    .set(INTEGRATION_CONNECTIONS.REFRESH_TOKEN, connection.getRefreshToken()) // Already encrypted
                    .set(INTEGRATION_CONNECTIONS.TOKEN_EXPIRES_AT, connection.getExpiresAt())
                    .set(INTEGRATION_CONNECTIONS.REFRESH_TOKEN_EXPIRES_AT, connection.getRefreshTokenExpiresAt())
                    .set(INTEGRATION_CONNECTIONS.REALM_ID, connection.getRealmId())
                    .set(INTEGRATION_CONNECTIONS.EXTERNAL_COMPANY_ID, connection.getExternalCompanyId())
                    .set(INTEGRATION_CONNECTIONS.EXTERNAL_COMPANY_NAME, connection.getExternalCompanyName())
                    .set(INTEGRATION_CONNECTIONS.DEFAULT_CURRENCY, connection.getDefaultCurrency() != null ? connection.getDefaultCurrency().getCode() : null)
                    .set(INTEGRATION_CONNECTIONS.CATEGORY, connection.getCategory() != null ? connection.getCategory().getValue() : "accounting")
                    .set(INTEGRATION_CONNECTIONS.SCOPES, connection.getScopes())
                    .set(INTEGRATION_CONNECTIONS.LAST_SYNC_AT, connection.getLastSyncAt())
                    .set(INTEGRATION_CONNECTIONS.ERROR_MESSAGE, connection.getErrorMessage())
                    .set(INTEGRATION_CONNECTIONS.METADATA, JSONB.jsonbOrNull(objectMapper.writeValueAsString(connection.getMetadata())))
                    .returning()
                    .fetchSingle();

            log.info("Created integration connection: id={}, companyId={}, provider={}",
                    record.getId(), connection.getCompanyId(), connection.getProvider());

            return toDomain(record);
        } catch (DuplicateKeyException e) {
            log.warn("Duplicate integration connection: companyId={}, provider={}",
                    connection.getCompanyId(), connection.getProvider());
            throw new DuplicateException("connection already exists", e);
        }
    }

    @Override
    public void updateTokens(String id, String accessToken, String refreshToken, OffsetDateTime expiresAt, OffsetDateTime refreshTokenExpiresAt) {
        try {
            // Note: Tokens should already be encrypted by the service layer before calling this method
            // Repository stores tokens as-is (encrypted strings)

            var update = dsl.update(INTEGRATION_CONNECTIONS)
                    .set(INTEGRATION_CONNECTIONS.ACCESS_TOKEN, accessToken) // Already encrypted
                    .set(INTEGRATION_CONNECTIONS.REFRESH_TOKEN, refreshToken) // Already encrypted
                    .set(INTEGRATION_CONNECTIONS.TOKEN_EXPIRES_AT, expiresAt)
                    .set(INTEGRATION_CONNECTIONS.UPDATED_AT, OffsetDateTime.now());

            // Only update refresh token expiry if provided
            if (refreshTokenExpiresAt != null) {
                    update = update.set(INTEGRATION_CONNECTIONS.REFRESH_TOKEN_EXPIRES_AT, refreshTokenExpiresAt);
            }

            int updated = update.where(INTEGRATION_CONNECTIONS.ID.eq(id))
                    .execute();

            if (updated == 0) {
                throw new IntegrationConnectionException("Integration connection not found: " + id);
            }

            log.debug("Updated tokens for connection: id={}", id);
        } catch (Exception e) {
            log.error("Failed to update tokens for connection: id={}", id, e);
            throw new IntegrationConnectionException("Failed to update tokens", e);
        }
    }

    @Override
    public IntegrationConnection updateStatus(String id, IntegrationConnectionStatus status, String errorMessage) {
        return dsl.update(INTEGRATION_CONNECTIONS)
                .set(INTEGRATION_CONNECTIONS.STATUS, status.getValue())
                .set(INTEGRATION_CONNECTIONS.ERROR_MESSAGE, errorMessage)
                .set(INTEGRATION_CONNECTIONS.UPDATED_AT, OffsetDateTime.now())
                .where(INTEGRATION_CONNECTIONS.ID.eq(id))
                .returning()
                .fetchOptional()
                .map(this::toDomain)
                .orElseThrow(() -> new NotFoundException("Integration connection not found: " + id));
    }

    @Override
    public IntegrationConnection updateLastSyncAt(String id, OffsetDateTime lastSyncAt) {
        return dsl.update(INTEGRATION_CONNECTIONS)
                .set(INTEGRATION_CONNECTIONS.LAST_SYNC_AT, lastSyncAt)
                .set(INTEGRATION_CONNECTIONS.UPDATED_AT, OffsetDateTime.now())
                .where(INTEGRATION_CONNECTIONS.ID.eq(id))
                .returning()
                .fetchOptional()
                .map(this::toDomain)
                .orElseThrow(() -> new NotFoundException("Integration connection not found: " + id));

    }

    @Override
    public void updatePreferences(String id, com.tosspaper.models.domain.integration.Preferences preferences) {
        var update = dsl.update(INTEGRATION_CONNECTIONS)
                .set(INTEGRATION_CONNECTIONS.UPDATED_AT, OffsetDateTime.now());
        
        // Update defaultCurrency if provided
        if (preferences.getDefaultCurrency() != null) {
            update = update.set(INTEGRATION_CONNECTIONS.DEFAULT_CURRENCY, preferences.getDefaultCurrency().getCode());
        }
        
        // Update multicurrencyEnabled if provided
        if (preferences.getMulticurrencyEnabled() != null) {
            update = update.set(INTEGRATION_CONNECTIONS.MULTICURRENCY_ENABLED, preferences.getMulticurrencyEnabled());
        }
        
        int updated = update.where(INTEGRATION_CONNECTIONS.ID.eq(id))
                .execute();

        if (updated == 0) {
            throw new NotFoundException("Integration connection not found: " + id);
        }

        log.debug("Updated preferences for connection: id={}, defaultCurrency={}, multicurrencyEnabled={}",
                id,
                preferences.getDefaultCurrency() != null ? preferences.getDefaultCurrency().getCode() : null,
                preferences.getMulticurrencyEnabled());
    }

    /**
     * Get default currency from record.
     */
    private Currency getDefaultCurrency(com.tosspaper.models.jooq.tables.records.IntegrationConnectionsRecord record) {
        String currencyCode = record.getDefaultCurrency();
        return currencyCode != null ? Currency.fromCode(currencyCode) : null;
    }

    /**
     * Get category from record.
     */
    private com.tosspaper.models.domain.integration.IntegrationCategory getCategory(com.tosspaper.models.jooq.tables.records.IntegrationConnectionsRecord record) {
        String categoryValue = record.getCategory();
        return categoryValue != null ? com.tosspaper.models.domain.integration.IntegrationCategory.fromValue(categoryValue) : null;
    }

    /**
     * Convert JOOQ record to a domain model.
     * Note: Tokens are returned as-is (encrypted). Service layer will decrypt them.
     */
    private IntegrationConnection toDomain(com.tosspaper.models.jooq.tables.records.IntegrationConnectionsRecord record) {
        try {
            // Deserialize metadata from JSONB
            Map<String, Object> metadata = new HashMap<>();
            if (record.getMetadata() != null) {
                try {
                    metadata = objectMapper.readValue(record.getMetadata().data(), new TypeReference<>() {}
                    );
                } catch (Exception e) {
                    log.warn("Failed to parse metadata for connection: id={}", record.getId(), e);
                }
            }

            return IntegrationConnection.builder()
                    .id(record.getId())
                    .companyId(record.getCompanyId())
                    .provider(IntegrationProvider.fromValue(record.getProvider()))
                    .status(IntegrationConnectionStatus.fromValue(record.getStatus()))
                    .accessToken(record.getAccessToken()) // Encrypted - service will decrypt
                    .refreshToken(record.getRefreshToken()) // Encrypted - service will decrypt
                    .expiresAt(record.getTokenExpiresAt())
                    .refreshTokenExpiresAt(record.getRefreshTokenExpiresAt() != null ? record.getRefreshTokenExpiresAt() : null)
                    .realmId(record.getRealmId())
                    .externalCompanyId(record.getExternalCompanyId())
                    .externalCompanyName(record.getExternalCompanyName())
                    .defaultCurrency(getDefaultCurrency(record))
                    .multicurrencyEnabled(record.getMulticurrencyEnabled())
                    .category(getCategory(record))
                    .scopes(record.getScopes())
                    .lastSyncAt(record.getLastSyncAt())
                    .errorMessage(record.getErrorMessage())
                    .metadata(metadata)
                    .lastPushCursor(record.getLastPushCursor())
                    .lastPushCursorAt(record.getLastPushCursorAt())
                    .syncFrom(record.getSyncFrom())
                    .createdAt(record.getCreatedAt())
                    .updatedAt(record.getUpdatedAt())
                    .build();
        } catch (Exception e) {
            log.error("Failed to convert record to domain: id={}", record.getId(), e);
            throw new IntegrationConnectionException("Failed to convert record to domain", e);
        }
    }
}

