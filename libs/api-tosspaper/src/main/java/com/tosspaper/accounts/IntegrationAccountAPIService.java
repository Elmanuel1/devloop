package com.tosspaper.accounts;

import com.tosspaper.generated.model.IntegrationAccountList;

/**
 * Service for Integration Account operations.
 * Extends the models service with API-specific methods.
 */
public interface IntegrationAccountAPIService{

    /**
     * Get integration accounts for a company.
     *
     * @param companyId company ID
     * @param accountType filter by account type (EXPENSE for expense accounts only, ALL for all accounts)
     * @return list of integration accounts
     */
    IntegrationAccountList getAccounts(Long companyId, AccountType accountType);
}
