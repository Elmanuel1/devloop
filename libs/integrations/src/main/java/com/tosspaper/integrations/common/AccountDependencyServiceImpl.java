package com.tosspaper.integrations.common;

import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of AccountDependencyService.
 * Validates that accounts have external IDs (they must be pulled from provider first).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDependencyServiceImpl implements AccountDependencyService {

    @Override
    public DependencyPushResult validateHaveExternalIds(
            IntegrationConnection connection,
            List<IntegrationAccount> accounts) {

        // Find accounts missing external IDs
        List<IntegrationAccount> missingExternalIds = accounts.stream()
            .filter(account -> account.getExternalId() == null)
            .toList();

        if (missingExternalIds.isEmpty()) {
            log.debug("All {} accounts have external IDs", accounts.size());
            return DependencyPushResult.success();
        }

        // Accounts are synced FROM provider (pull), not TO provider (push)
        // If an account lacks an externalId, it means it hasn't been synced yet
        String accountNames = missingExternalIds.stream()
            .map(IntegrationAccount::getName)
            .collect(Collectors.joining(", "));

        String errorMsg = String.format(
            "Found %d account(s) without external IDs: [%s]. " +
            "Accounts must be synced FROM %s before they can be used in purchase orders. " +
            "Please run a pull sync to import accounts from the provider.",
            missingExternalIds.size(),
            accountNames,
            connection.getProvider()
        );

        log.error(errorMsg);
        return DependencyPushResult.failure(errorMsg);
    }
}
