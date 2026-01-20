package com.tosspaper.models.service;

import com.tosspaper.models.domain.Currency;

/**
 * Service for company sync operations.
 * Used for syncing company data from external providers.
 */
public interface CompanySyncService {

    /**
     * Update company currency and multicurrency settings from integration preferences.
     * Keeps company settings in sync with the integration's base currency and multicurrency status.
     *
     * @param companyId company ID
     * @param currency default currency from integration provider
     * @param multicurrencyEnabled whether multicurrency is enabled in the integration provider
     */
    void updateCurrencyFromIntegration(Long companyId, Currency currency, Boolean multicurrencyEnabled);
}

