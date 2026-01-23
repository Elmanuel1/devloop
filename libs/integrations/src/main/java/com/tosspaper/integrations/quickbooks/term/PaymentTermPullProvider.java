package com.tosspaper.integrations.quickbooks.term;

import com.intuit.ipp.data.Term;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTermPullProvider implements IntegrationPullProvider<PaymentTerm> {

    private final QuickBooksApiClient apiClient;
    private final PaymentTermMapper paymentTermMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.PAYMENT_TERM;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public List<PaymentTerm> pullBatch(IntegrationConnection connection) {
        return apiClient.queryPaymentTermsSinceLastSync(connection);
    }

    @Override
    public PaymentTerm getById(String externalId, IntegrationConnection connection) {
        if (externalId == null || externalId.isBlank()) {
            return null;
        }
        String query = "SELECT * FROM Term WHERE Id = '" + externalId + "'";
        List<Term> terms = apiClient.queryTerms(connection, query);
        return terms.isEmpty() ? null : paymentTermMapper.toDomain(terms.getFirst());
    }
}
