package com.tosspaper.integrations.oauth;

import java.time.Duration;

/**
 * Service for managing OAuth state tokens.
 * Handles storage and validation of CSRF protection tokens.
 */
public interface OAuthStateService {

    /**
     * Store OAuth state in Redis for CSRF protection.
     * Stores both companyId and providerId.
     *
     * @param state     the state token
     * @param companyId the company ID associated with this OAuth flow
     * @param providerId the provider ID (e.g., "quickbooks")
     */
    void storeState(String state, Long companyId, String providerId);

    /**
     * Validate and consume OAuth state.
     * Returns the company ID and provider ID associated with the state.
     *
     * @param state the state token to validate
     * @return StateData containing companyId and providerId
     * @throws com.tosspaper.integrations.common.exception.IntegrationAuthException if state is invalid or expired
     */
    StateData validateAndConsumeState(String state);

    /**
     * State data stored in Redis.
     */
    record StateData(Long companyId, String providerId) {}
}
