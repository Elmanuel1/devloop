package com.tosspaper.integrations.quickbooks.account;

import com.intuit.ipp.data.Account;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountPullProvider implements IntegrationPullProvider<IntegrationAccount> {

    private final QuickBooksApiClient apiClient;
    private final AccountMapper accountMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.ACCOUNT;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<IntegrationAccount> pullBatch(IntegrationConnection connection) {
        return apiClient.queryAccountsSinceLastSync(connection);
    }

    @Override
    public IntegrationAccount getById(String externalId, IntegrationConnection connection) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        String query = "SELECT * FROM Account WHERE Id = '" + externalId + "'";
        List<Account> accounts = apiClient.executeQuery(connection, query);
        return accounts.isEmpty() ? null : accountMapper.toDomain(accounts.getFirst());
    }
}
