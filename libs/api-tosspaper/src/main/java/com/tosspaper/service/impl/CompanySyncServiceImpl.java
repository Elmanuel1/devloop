package com.tosspaper.service.impl;

import com.tosspaper.company.CompanyRepository;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.jooq.tables.records.CompaniesRecord;
import com.tosspaper.models.service.CompanySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Implementation of CompanySyncService.
 * Handles syncing company data from external providers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanySyncServiceImpl implements CompanySyncService {

    private final CompanyRepository companyRepository;

    @Override
    public void updateCurrencyFromIntegration(Long companyId, Currency currency, Boolean multicurrencyEnabled) {
        try {
            CompaniesRecord company = companyRepository.findById(companyId);

            // Update currency and multicurrency_enabled if provided
            if (currency != null || multicurrencyEnabled != null) {
                if (currency != null) {
                    company.setCurrency(currency.getCode());
                }
                if (multicurrencyEnabled != null) {
                    company.setMulticurrencyEnabled(multicurrencyEnabled);
                }
                companyRepository.update(company);
                log.info("Updated company {} settings from integration: currency={}, multicurrencyEnabled={}", 
                        companyId, 
                        currency != null ? currency.getCode() : "unchanged",
                        multicurrencyEnabled != null ? multicurrencyEnabled : "unchanged");
            }
        } catch (Exception e) {
            log.warn("Failed to update company settings from integration: companyId={}, currency={}, multicurrencyEnabled={}", 
                    companyId, 
                    currency != null ? currency.getCode() : null, 
                    multicurrencyEnabled, e);
            // Don't throw - this is a sync operation that shouldn't fail the workflow
        }
    }
}

