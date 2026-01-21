package com.tosspaper.accounts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.tosspaper.models.jooq.Tables.INTEGRATION_ACCOUNTS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class IntegrationAccountRepositoryImpl implements IntegrationAccountRepository {
    
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    
    @Override
    @SneakyThrows
    public void upsert(String connectionId, List<IntegrationAccount> accounts) {
        for (IntegrationAccount account : accounts) {
            dsl.insertInto(INTEGRATION_ACCOUNTS)
                .set(INTEGRATION_ACCOUNTS.CONNECTION_ID, connectionId)
                .set(INTEGRATION_ACCOUNTS.EXTERNAL_ID, account.getExternalId())
                .set(INTEGRATION_ACCOUNTS.NAME, account.getName())
                .set(INTEGRATION_ACCOUNTS.ACCOUNT_TYPE, account.getAccountType())
                .set(INTEGRATION_ACCOUNTS.ACCOUNT_SUB_TYPE, account.getAccountSubType())
                .set(INTEGRATION_ACCOUNTS.CLASSIFICATION, account.getClassification())
                .set(INTEGRATION_ACCOUNTS.ACTIVE, account.getActive())
                .set(INTEGRATION_ACCOUNTS.CURRENT_BALANCE, account.getCurrentBalance())
                .set(INTEGRATION_ACCOUNTS.EXTERNAL_METADATA, JSONB.jsonbOrNull(objectMapper.writeValueAsString(account.getExternalMetadata())))
                .set(INTEGRATION_ACCOUNTS.PROVIDER_CREATED_AT, account.getProviderCreatedAt())
                .set(INTEGRATION_ACCOUNTS.PROVIDER_LAST_UPDATED_AT, account.getProviderLastUpdatedAt())
                .onConflict(INTEGRATION_ACCOUNTS.CONNECTION_ID, INTEGRATION_ACCOUNTS.EXTERNAL_ID)
                .doUpdate()
                .set(INTEGRATION_ACCOUNTS.NAME, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.NAME))
                .set(INTEGRATION_ACCOUNTS.ACCOUNT_TYPE, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.ACCOUNT_TYPE))
                .set(INTEGRATION_ACCOUNTS.ACCOUNT_SUB_TYPE, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.ACCOUNT_SUB_TYPE))
                .set(INTEGRATION_ACCOUNTS.CLASSIFICATION, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.CLASSIFICATION))
                .set(INTEGRATION_ACCOUNTS.ACTIVE, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.ACTIVE))
                .set(INTEGRATION_ACCOUNTS.CURRENT_BALANCE, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.CURRENT_BALANCE))
                .set(INTEGRATION_ACCOUNTS.EXTERNAL_METADATA, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.EXTERNAL_METADATA))
                .set(INTEGRATION_ACCOUNTS.PROVIDER_LAST_UPDATED_AT, org.jooq.impl.DSL.excluded(INTEGRATION_ACCOUNTS.PROVIDER_LAST_UPDATED_AT))
                .execute();
        }
    }
    
    @Override
    public List<IntegrationAccount> findByConnectionId(String connectionId) {
        return dsl.selectFrom(INTEGRATION_ACCOUNTS)
            .where(INTEGRATION_ACCOUNTS.CONNECTION_ID.eq(connectionId))
            .fetch()
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<IntegrationAccount> findByCompanyId(Long companyId, AccountType accountType) {
        var query = dsl.select(INTEGRATION_ACCOUNTS.fields())
            .from(INTEGRATION_ACCOUNTS)
            .join(com.tosspaper.models.jooq.Tables.INTEGRATION_CONNECTIONS)
            .on(INTEGRATION_ACCOUNTS.CONNECTION_ID.eq(com.tosspaper.models.jooq.Tables.INTEGRATION_CONNECTIONS.ID))
            .where(com.tosspaper.models.jooq.Tables.INTEGRATION_CONNECTIONS.COMPANY_ID.eq(companyId));

        // Apply account type filter if specified (null means no filtering)
        if (accountType != null) {
            query = query.and(INTEGRATION_ACCOUNTS.ACCOUNT_TYPE.in(accountType.getAccountTypes()));
        }

        return query.fetch()
            .map(record -> toDomain(record.into(INTEGRATION_ACCOUNTS)));
    }

    @Override
    public IntegrationAccount findById(String id) {
        return dsl.selectFrom(INTEGRATION_ACCOUNTS)
            .where(INTEGRATION_ACCOUNTS.ID.eq(id))
            .fetchOptional()
            .map(this::toDomain)
            .orElse(null);
    }

    @Override
    public List<IntegrationAccount> findByIds(String connectionId, List<String> ids) {
        if (connectionId == null || ids == null || ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(INTEGRATION_ACCOUNTS)
            .where(INTEGRATION_ACCOUNTS.CONNECTION_ID.eq(connectionId))
            .and(INTEGRATION_ACCOUNTS.ID.in(ids))
            .fetch()
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public Map<String, String> findIdsByExternalIdsAndConnection(String connectionId, List<String> externalIds) {
        if (connectionId == null || externalIds == null || externalIds.isEmpty()) {
            return Map.of();
        }
        
        return dsl.select(INTEGRATION_ACCOUNTS.EXTERNAL_ID, INTEGRATION_ACCOUNTS.ID)
            .from(INTEGRATION_ACCOUNTS)
            .where(INTEGRATION_ACCOUNTS.CONNECTION_ID.eq(connectionId))
            .and(INTEGRATION_ACCOUNTS.EXTERNAL_ID.in(externalIds))
            .fetchMap(INTEGRATION_ACCOUNTS.EXTERNAL_ID, INTEGRATION_ACCOUNTS.ID);
    }

    private IntegrationAccount toDomain(com.tosspaper.models.jooq.tables.records.IntegrationAccountsRecord record) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (record.getExternalMetadata() != null) {
                try {
                    metadata = objectMapper.readValue(record.getExternalMetadata().data(), new TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to parse metadata for account: id={}", record.getId(), e);
                }
            }

            IntegrationAccount account = IntegrationAccount.builder()
                .id(record.getId())
                .connectionId(record.getConnectionId())
                .name(record.getName())
                .accountType(record.getAccountType())
                .accountSubType(record.getAccountSubType())
                .classification(record.getClassification())
                .active(record.getActive())
                .currentBalance(record.getCurrentBalance())
                .createdAt(record.getCreatedAt())
                .build();
            // Set inherited ProviderTracked fields via setters (not in builder)
            account.setExternalId(record.getExternalId());
            account.setExternalMetadata(metadata);
            account.setProviderCreatedAt(record.getProviderCreatedAt());
            account.setProviderLastUpdatedAt(record.getProviderLastUpdatedAt());
            return account;
        } catch (Exception e) {
            log.error("Failed to convert record to domain: id={}", record.getId(), e);
            throw new RuntimeException("Failed to convert record to domain", e);
        }
    }
}


