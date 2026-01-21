package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationConnection;

import java.util.List;

/**
 * Service for validating that accounts have external IDs.
 * Unlike items and vendors, accounts are synced FROM the provider (pull),
 * so this service only validates they exist - it does not push them.
 */
public interface AccountDependencyService {

    /**
     * Validates that all accounts have external IDs.
     * Accounts without external IDs indicate they haven't been pulled from the provider yet.
     *
     * @param connection the integration connection
     * @param accounts list of accounts to validate
     * @return result indicating success or failure with error details
     */
    DependencyPushResult validateHaveExternalIds(
        IntegrationConnection connection,
        List<IntegrationAccount> accounts
    );
}
