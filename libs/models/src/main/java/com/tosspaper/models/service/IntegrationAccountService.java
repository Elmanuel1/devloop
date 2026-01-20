package com.tosspaper.models.service;

import com.tosspaper.models.domain.integration.IntegrationAccount;

import java.util.List;
import java.util.Map;

/**
 * Service for integration accounts operations.
 * Used for syncing chart of accounts from external providers.
 * Connection-specific (not company-wide).
 */
public interface IntegrationAccountService {
    
    /**
     * Upsert accounts from provider.
     * Creates new accounts or updates existing ones based on connection_id + external_id.
     * 
     * @param connectionId connection ID
     * @param accounts list of accounts to upsert
     */
    void upsert(String connectionId, List<IntegrationAccount> accounts);
    
    /**
     * Find all accounts for a connection.
     *
     * @param connectionId connection ID
     * @return list of accounts
     */
    List<IntegrationAccount> findByConnectionId(String connectionId);

    /**
     * Find account by ID.
     *
     * @param id account ID (ULID string)
     * @return account if found, null otherwise
     */
    IntegrationAccount findById(String id);

    /**
     * Find accounts by IDs (batch lookup).
     *
     * @param connectionId connection ID
     * @param ids list of account IDs
     * @return list of accounts found
     */
    List<IntegrationAccount> findByIds(String connectionId, List<String> ids);

    /**
     * Batch lookup accounts by external IDs and connection ID.
     * Returns a map of external_id -> account.id for efficient resolution.
     *
     * @param connectionId connection ID
     * @param externalIds list of external IDs to lookup
     * @return map of external_id to internal account id
     */
    Map<String, String> findIdsByExternalIdsAndConnection(String connectionId, List<String> externalIds);
}



