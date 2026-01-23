package com.tosspaper.integrations.quickbooks.preferences;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.Currency;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.domain.integration.Preferences;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PreferencesPullProvider implements IntegrationPullProvider<Preferences> {

    private final QuickBooksApiClient apiClient;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.PREFERENCES;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<Preferences> pullBatch(IntegrationConnection connection) {
        List<com.intuit.ipp.data.Preferences> qboPrefsList = apiClient.executeQuery(connection, "SELECT * FROM Preferences");
        
        if (qboPrefsList == null || qboPrefsList.isEmpty()) {
            return List.of();
        }

        com.intuit.ipp.data.Preferences qboPrefs = qboPrefsList.getFirst();
        String currencyCode = null;
        Boolean multicurrencyEnabled = null;
        
        if (qboPrefs.getCurrencyPrefs() != null) {
            com.intuit.ipp.data.CurrencyPrefs currencyPrefs = qboPrefs.getCurrencyPrefs();
            
            if (currencyPrefs.getHomeCurrency() != null &&
                currencyPrefs.getHomeCurrency().getValue() != null) {
                currencyCode = currencyPrefs.getHomeCurrency().getValue();
            }
            
            // Extract multicurrency enabled flag
            if (currencyPrefs.isMultiCurrencyEnabled() != null) {
                multicurrencyEnabled = currencyPrefs.isMultiCurrencyEnabled();
            }
        }

        Currency currency = currencyCode != null ? Currency.fromQboValue(currencyCode) : null;
        
        return List.of(Preferences.builder()
                .defaultCurrency(currency)
                .multicurrencyEnabled(multicurrencyEnabled)
                .build());
    }

    @Override
    public Preferences getById(String externalId, IntegrationConnection connection) {
        // Preferences don't have an ID - return from pullBatch
        List<Preferences> prefs = pullBatch(connection);
        return prefs.isEmpty() ? null : prefs.getFirst();
    }
}

